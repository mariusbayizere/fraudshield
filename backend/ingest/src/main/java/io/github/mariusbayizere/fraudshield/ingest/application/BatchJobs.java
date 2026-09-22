package io.github.mariusbayizere.fraudshield.ingest.application;

import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.idempotency.IdempotencyStore;
import io.github.mariusbayizere.fraudshield.ingest.request.RequestValidator;
import io.github.mariusbayizere.fraudshield.ingest.request.ValidationError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Batch ingestion (FR-01-06, E.1): up to 1,000 transactions accepted with 202 and decided
 * asynchronously by the same ingest path as single submissions, so every item follows the same
 * validation and idempotency rules (V3 {@code batch_jobs}, {@code batch_job_items}). A job decides
 * up to 16 items at once on virtual threads and records progress every 50. An item that cannot be
 * decided because a dependency is down is retried; if it still cannot be, the job is marked FAILED
 * and the items not reached stay unrecorded.
 */
public final class BatchJobs {

  /** Most items per batch (FR-01-06). */
  public static final int MAX_ITEMS = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(BatchJobs.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int ATTEMPTS = 5;
  private static final int PARALLELISM = 16;
  private static final int PROGRESS_EVERY = 50;

  private static final String CREATE =
      """
      INSERT INTO batch_jobs (id, institution_id, api_key_id, state, total)
      VALUES (?, ?, ?, 'QUEUED', ?)
      """;
  private static final String PROGRESS =
      """
      UPDATE batch_jobs SET state = ?, processed = ?, failed = ?, completed_at = ?
      WHERE id = ?
      """;
  private static final String ITEM =
      """
      INSERT INTO batch_job_items (job_id, institution_id, position, transaction_id, outcome,
      problem) VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING
      """;
  private static final String UNFINISHED_INSTITUTIONS =
      "SELECT institutions_with_unfinished_batch_jobs FROM"
          + " institutions_with_unfinished_batch_jobs()";
  private static final String FAIL_UNFINISHED =
      "UPDATE batch_jobs SET state = 'FAILED', completed_at = ?"
          + " WHERE state IN ('QUEUED', 'RUNNING')";
  private static final String JOB =
      "SELECT state, total, processed, failed FROM batch_jobs WHERE id = ?";
  private static final String ITEMS =
      """
      SELECT position, transaction_id, outcome, problem::text FROM batch_job_items
      WHERE job_id = ? AND position >= ? ORDER BY position LIMIT ?
      """;

  /**
   * An accepted batch.
   *
   * @param jobId the job
   * @param accepted item count
   */
  public record Accepted(UUID jobId, int accepted) {}

  private final DataSource dataSource;
  private final IngestService ingest;
  private final IdempotencyStore idempotency;
  private final ExecutorService executor;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param dataSource connections as {@code fs_app}
   * @param ingest the single-submission path
   * @param idempotency cached responses, for job results
   * @param executor bounded executor that runs jobs
   * @param clock clock
   */
  public BatchJobs(
      DataSource dataSource,
      IngestService ingest,
      IdempotencyStore idempotency,
      ExecutorService executor,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ingest = Objects.requireNonNull(ingest, "ingest");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Validates the envelope, records the job and starts it.
   *
   * @param principal the key (with {@code ingest:write})
   * @param body the batch body
   * @return the job, or the envelope's validation errors
   * @throws SQLException when the job cannot be recorded
   */
  public Object submit(ApiPrincipal principal, JsonNode body) throws SQLException {
    List<ValidationError> errors = new java.util.ArrayList<>();
    if (body == null || !body.isObject()) {
      errors.add(new ValidationError("", "type_mismatch", "the body must be a JSON object"));
    } else {
      for (String name : body.propertyNames()) {
        if (!name.equals("transactions")) {
          errors.add(new ValidationError(name, "unknown_field", "not a field of this request"));
        }
      }
      JsonNode items = body.get("transactions");
      if (items == null) {
        errors.add(new ValidationError("transactions", "required", "transactions is required"));
      } else if (!items.isArray()) {
        errors.add(new ValidationError("transactions", "type_mismatch", "transactions is a list"));
      } else if (items.isEmpty() || items.size() > MAX_ITEMS) {
        errors.add(
            new ValidationError(
                "transactions",
                "item_count_out_of_range",
                "a batch holds 1 to " + MAX_ITEMS + " transactions"));
      }
    }
    if (!errors.isEmpty()) {
      return new RequestValidator.Result(null, errors);
    }
    ArrayNode items = (ArrayNode) body.get("transactions");
    UUID job = UUID.randomUUID();
    try (Connection c = tenant(principal.institutionId())) {
      try (PreparedStatement s = c.prepareStatement(CREATE)) {
        s.setObject(1, job);
        s.setObject(2, principal.institutionId());
        s.setObject(3, principal.apiKeyId());
        s.setInt(4, items.size());
        s.executeUpdate();
      }
      c.commit();
    }
    executor.execute(() -> run(principal, job, items));
    return new Accepted(job, items.size());
  }

  private void run(ApiPrincipal principal, UUID job, ArrayNode items) {
    AtomicInteger processed = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    AtomicBoolean unavailable = new AtomicBoolean();
    UUID institution = principal.institutionId();
    try {
      progress(institution, job, "RUNNING", 0, 0, null);
      Semaphore permits = new Semaphore(PARALLELISM);
      try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < items.size() && !unavailable.get(); i++) {
          permits.acquire();
          final int position = i;
          workers.execute(
              () -> {
                try {
                  JsonNode item = items.get(position);
                  IngestService.Outcome outcome = decide(principal, item);
                  if (outcome instanceof IngestService.Unavailable) {
                    unavailable.set(true);
                    return;
                  }
                  String problem = problem(outcome);
                  item(institution, job, position, transactionId(item), problem);
                  int done = processed.incrementAndGet();
                  int rejected = problem == null ? failed.get() : failed.incrementAndGet();
                  if (done % PROGRESS_EVERY == 0) {
                    progress(institution, job, "RUNNING", done, rejected, null);
                  }
                } catch (SQLException | RuntimeException e) {
                  LOG.error("a batch item failed", e);
                  unavailable.set(true);
                } finally {
                  permits.release();
                }
              });
        }
      }
      progress(
          institution,
          job,
          unavailable.get() ? "FAILED" : "COMPLETED",
          processed.get(),
          failed.get(),
          clock.instant());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (SQLException | RuntimeException e) {
      LOG.error("batch job failed", e);
      try {
        progress(institution, job, "FAILED", processed.get(), failed.get(), clock.instant());
      } catch (SQLException ignored) {
        LOG.error("could not mark a batch job failed", ignored);
      }
    }
  }

  private IngestService.Outcome decide(ApiPrincipal principal, JsonNode item) {
    IngestService.Outcome outcome = null;
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      outcome = ingest.ingest(principal, item, System.nanoTime());
      if (!(outcome instanceof IngestService.Unavailable)) {
        return outcome;
      }
      java.util.concurrent.locks.LockSupport.parkNanos(
          Duration.ofMillis(200L << attempt).toNanos());
    }
    return outcome;
  }

  private static String problem(IngestService.Outcome outcome) {
    return switch (outcome) {
      case IngestService.Decided decided -> null;
      case IngestService.Invalid invalid ->
          JSON.writeValueAsString(Problems.validation(invalid.result(), UUID.randomUUID()));
      case IngestService.Conflict conflict ->
          JSON.writeValueAsString(
              Problems.problem(
                  "idempotency-conflict",
                  "Transaction already submitted",
                  409,
                  "the transaction_id was submitted with a different request",
                  UUID.randomUUID()));
      case IngestService.Unavailable unavailable -> throw new IllegalStateException("retried");
    };
  }

  private static UUID transactionId(JsonNode item) {
    try {
      return UUID.fromString(item.path("transaction_id").asString(""));
    } catch (IllegalArgumentException invalid) {
      // An item without a usable id is reported under the nil UUID, with its validation problem.
      return new UUID(0, 0);
    }
  }

  /**
   * Marks jobs left QUEUED or RUNNING by a process that died as FAILED (V65).
   *
   * <p>A job's items live in memory while it runs, so nothing else can finish one whose instance is
   * gone: without this, a job answered 202 stays RUNNING for ever (Principal Review finding 12). It
   * runs at start-up, so the jobs it sees are earlier processes' leftovers.
   *
   * @return the jobs it failed
   * @throws SQLException when the database is unavailable
   */
  public int failUnfinishedJobs() throws SQLException {
    List<UUID> institutions = new java.util.ArrayList<>();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try (PreparedStatement s = c.prepareStatement(UNFINISHED_INSTITUTIONS);
          ResultSet rows = s.executeQuery()) {
        while (rows.next()) {
          institutions.add(rows.getObject(1, UUID.class));
        }
      }
      c.commit();
    }
    int failed = 0;
    for (UUID institution : institutions) {
      try (Connection c = tenant(institution);
          PreparedStatement s = c.prepareStatement(FAIL_UNFINISHED)) {
        s.setObject(1, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        failed += s.executeUpdate();
        c.commit();
      }
    }
    if (failed > 0) {
      LOG.warn("{} batch jobs were left unfinished by an earlier process and are FAILED", failed);
    }
    return failed;
  }

  /**
   * A job's status (OpenAPI {@code JobStatus}), paginated by position.
   *
   * @param principal the key (with {@code jobs:read})
   * @param job the job
   * @param cursor position to start at, or null
   * @param limit most results, 1 to 200
   * @return the status, or empty when the job is not the institution's
   * @throws SQLException when the database is unavailable
   */
  public Optional<ObjectNode> status(ApiPrincipal principal, UUID job, String cursor, int limit)
      throws SQLException {
    if (cursor != null && !cursor.matches("^[0-9]{1,7}$")) {
      // The controller validates it too; this keeps any other caller off Integer.parseInt.
      throw new IllegalArgumentException("cursor");
    }
    int from = cursor == null ? 0 : Integer.parseInt(cursor);
    try (Connection c = tenant(principal.institutionId())) {
      ObjectNode status = JSON.createObjectNode();
      try (PreparedStatement s = c.prepareStatement(JOB)) {
        s.setObject(1, job);
        try (ResultSet row = s.executeQuery()) {
          if (!row.next()) {
            c.rollback();
            return Optional.empty();
          }
          status.put("job_id", job.toString());
          status.put("state", row.getString(1));
          status.put("total", row.getInt(2));
          status.put("processed", row.getInt(3));
          status.put("failed", row.getInt(4));
        }
      }
      ArrayNode results = status.putArray("results");
      try (PreparedStatement s = c.prepareStatement(ITEMS)) {
        s.setObject(1, job);
        s.setInt(2, from);
        s.setInt(3, limit + 1);
        try (ResultSet row = s.executeQuery()) {
          while (row.next()) {
            if (results.size() == limit) {
              status.put("next_cursor", Integer.toString(row.getInt(1)));
              break;
            }
            ObjectNode result = results.addObject();
            UUID transaction = row.getObject(2, UUID.class);
            result.put("transaction_id", transaction.toString());
            result.put("outcome", row.getString(3));
            if (row.getString(4) != null) {
              result.set("problem", JSON.readTree(row.getString(4)));
            } else {
              idempotency
                  .decided(principal.institutionId(), transaction)
                  .ifPresent(bytes -> result.set("decision", JSON.readTree(bytes)));
            }
          }
        }
      }
      if (!status.has("next_cursor")) {
        status.putNull("next_cursor");
      }
      c.commit();
      return Optional.of(status);
    }
  }

  private void item(UUID institution, UUID job, int position, UUID transaction, String problem)
      throws SQLException {
    try (Connection c = tenant(institution);
        PreparedStatement s = c.prepareStatement(ITEM)) {
      s.setObject(1, job);
      s.setObject(2, institution);
      s.setInt(3, position);
      s.setObject(4, transaction);
      s.setString(5, problem == null ? "DECIDED" : "REJECTED");
      s.setString(6, problem);
      s.executeUpdate();
      c.commit();
    }
  }

  private void progress(
      UUID institution, UUID job, String state, int processed, int failed, Instant completed)
      throws SQLException {
    try (Connection c = tenant(institution);
        PreparedStatement s = c.prepareStatement(PROGRESS)) {
      s.setString(1, state);
      s.setInt(2, processed);
      s.setInt(3, failed);
      s.setObject(
          4, completed == null ? null : OffsetDateTime.ofInstant(completed, ZoneOffset.UTC));
      s.setObject(5, job);
      s.executeUpdate();
      c.commit();
    }
  }

  private Connection tenant(UUID institution) throws SQLException {
    Connection c = dataSource.getConnection();
    try {
      c.setAutoCommit(false);
      try (PreparedStatement s =
          c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
        s.setString(1, institution.toString());
        s.execute();
      }
      return c;
    } catch (SQLException e) {
      c.close();
      throw e;
    }
  }
}
