package io.github.mariusbayizere.fraudshield.ingest.web;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.ingest.application.BatchJobs;
import io.github.mariusbayizere.fraudshield.ingest.application.IngestService;
import io.github.mariusbayizere.fraudshield.ingest.application.Problems;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiScope;
import io.github.mariusbayizere.fraudshield.ingest.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.ingest.request.RequestValidator;
import io.github.mariusbayizere.fraudshield.ingest.request.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The machine-to-machine ingestion API (E.1, OpenAPI tag {@code ingestion}). Controllers only
 * translate HTTP: every decision is the application services'.
 */
@RestController
public final class IngestController {

  /** Largest single ingest body (E.1: 413 above it). */
  public static final int MAX_BODY = 16 * 1024;

  /** Largest batch body: 1,000 items with room to spare. */
  public static final int MAX_BATCH_BODY = 4 * 1024 * 1024;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final IngestService ingest;
  private final BatchJobs batches;
  private final DecisionStatePort states;
  private final RateLimiter limiter;

  /**
   * Creates the controller.
   *
   * @param ingest single submissions
   * @param batches batch jobs
   * @param states decision states
   * @param limiter the per-key budget, which charges a batch by its item count
   */
  public IngestController(
      IngestService ingest, BatchJobs batches, DecisionStatePort states, RateLimiter limiter) {
    this.ingest = Objects.requireNonNull(ingest, "ingest");
    this.batches = Objects.requireNonNull(batches, "batches");
    this.states = Objects.requireNonNull(states, "states");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  /**
   * {@code POST /api/v1/transactions/ingest}.
   *
   * @param request the request
   * @return the decision or a problem
   * @throws IOException when the body cannot be read
   */
  @PostMapping("/api/v1/transactions/ingest")
  public ResponseEntity<byte[]> ingest(HttpServletRequest request) throws IOException {
    final long received = System.nanoTime();
    UUID correlation = ApiKeyFilter.correlation(request);
    Optional<ResponseEntity<byte[]>> refused = refuse(request, ApiScope.INGEST_WRITE, MAX_BODY);
    if (refused.isPresent()) {
      return refused.get();
    }
    byte[] raw = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (raw.length > MAX_BODY) {
      // No Content-Length (a chunked body): the limit is found by reading one byte past it, so an
      // oversized body is 413 rather than a truncated 400 (Principal Review finding 13).
      return tooLarge(MAX_BODY, correlation);
    }
    JsonNode body;
    try {
      body = JSON.readTree(raw);
    } catch (JacksonException malformed) {
      return problem(Problems.validation(RequestValidator.malformed(), correlation));
    }
    IngestService.Outcome outcome = ingest.ingest(principal(request), body, received);
    return switch (outcome) {
      case IngestService.Decided decided -> {
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        if (decided.replayed()) {
          ok.header("Idempotent-Replayed", "true");
        }
        yield ok.body(decided.body());
      }
      case IngestService.Invalid invalid ->
          problem(Problems.validation(invalid.result(), correlation));
      case IngestService.Conflict conflict ->
          problem(
              Problems.problem(
                  "idempotency-conflict",
                  "Transaction already submitted",
                  409,
                  "this transaction_id was submitted within 24 hours with a different request;"
                      + " nothing was scored",
                  correlation));
      case IngestService.Unavailable unavailable -> unavailable(correlation);
    };
  }

  /**
   * {@code POST /api/v1/transactions/ingest/batch}.
   *
   * @param request the request
   * @return 202 with the job, or a problem
   * @throws IOException when the body cannot be read
   * @throws SQLException when the job cannot be recorded
   */
  @PostMapping("/api/v1/transactions/ingest/batch")
  public ResponseEntity<byte[]> batch(HttpServletRequest request) throws IOException, SQLException {
    UUID correlation = ApiKeyFilter.correlation(request);
    Optional<ResponseEntity<byte[]>> refused =
        refuse(request, ApiScope.INGEST_WRITE, MAX_BATCH_BODY);
    if (refused.isPresent()) {
      // A refusal still costs one unit, as any request does (unless there is no key to charge).
      return charged(request, 1, correlation, refused.get());
    }
    byte[] raw = request.getInputStream().readNBytes(MAX_BATCH_BODY + 1);
    if (raw.length > MAX_BATCH_BODY) {
      return charged(request, 1, correlation, tooLarge(MAX_BATCH_BODY, correlation));
    }
    JsonNode body;
    try {
      body = JSON.readTree(raw);
    } catch (JacksonException malformed) {
      return charged(
          request,
          1,
          correlation,
          problem(Problems.validation(RequestValidator.malformed(), correlation)));
    }
    // The budget is counted in transactions (ADR 0058, adopting ADR 0100): a batch costs its item
    // count, charged here once and all or nothing, before anything is accepted. An envelope that
    // is not a valid batch costs one unit, like any other request.
    int units = BatchJobs.chargeableUnits(body);
    RateLimiter.Permit permit = limiter.take(principal(request).apiKeyId(), units);
    if (!permit.allowed()) {
      return withBudget(
          ResponseEntity.status(429)
              .header("Retry-After", Integer.toString(permit.retryAfterSeconds()))
              .contentType(MediaType.APPLICATION_PROBLEM_JSON)
              .body(RateLimitFilter.refusal(permit, units, correlation)),
          permit);
    }
    Object submitted = batches.submit(principal(request), body);
    if (submitted instanceof RequestValidator.Result invalid) {
      return withBudget(problem(Problems.validation(invalid, correlation)), permit);
    }
    BatchJobs.Accepted accepted = (BatchJobs.Accepted) submitted;
    String location = "/api/v1/jobs/" + accepted.jobId();
    ObjectNode node = JSON.createObjectNode();
    node.put("job_id", accepted.jobId().toString());
    node.put("accepted", accepted.accepted());
    node.put("status_url", location);
    return withBudget(
        ResponseEntity.accepted()
            .header(HttpHeaders.LOCATION, location)
            .contentType(MediaType.APPLICATION_JSON)
            .body(JSON.writeValueAsBytes(node)),
        permit);
  }

  /**
   * Charges a batch request that ends in {@code answer} without a job: one unit, as any request.
   * Over budget, the answer is the 429 instead; without an API key there is no budget to charge.
   */
  private ResponseEntity<byte[]> charged(
      HttpServletRequest request, int units, UUID correlation, ResponseEntity<byte[]> answer) {
    ApiPrincipal key = principal(request);
    if (key == null) {
      return answer;
    }
    RateLimiter.Permit permit = limiter.take(key.apiKeyId(), units);
    if (!permit.allowed()) {
      return withBudget(
          ResponseEntity.status(429)
              .header("Retry-After", Integer.toString(permit.retryAfterSeconds()))
              .contentType(MediaType.APPLICATION_PROBLEM_JSON)
              .body(RateLimitFilter.refusal(permit, units, correlation)),
          permit);
    }
    return withBudget(answer, permit);
  }

  private static ResponseEntity<byte[]> withBudget(
      ResponseEntity<byte[]> answer, RateLimiter.Permit permit) {
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(answer.getHeaders());
    RateLimitFilter.headers(permit).forEach(headers::set);
    return new ResponseEntity<>(answer.getBody(), headers, answer.getStatusCode());
  }

  /**
   * {@code GET /api/v1/jobs/{job_id}}.
   *
   * @param request the request
   * @param jobId the job
   * @param cursor pagination cursor
   * @param limit page size
   * @return the job status or 404
   * @throws SQLException when the database is unavailable
   */
  @GetMapping("/api/v1/jobs/{job_id}")
  public ResponseEntity<byte[]> job(
      HttpServletRequest request,
      @PathVariable("job_id") String jobId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "200") int limit)
      throws SQLException {
    UUID correlation = ApiKeyFilter.correlation(request);
    Optional<ResponseEntity<byte[]>> refused = scope(request, ApiScope.JOBS_READ);
    if (refused.isPresent()) {
      return refused.get();
    }
    Optional<UUID> id = uuid(jobId);
    if (id.isEmpty()
        || (cursor != null && !cursor.matches("^[0-9]{1,4}$"))
        || limit < 1
        || limit > 200) {
      return problem(
          Problems.validation(
              new RequestValidator.Result(
                  null,
                  List.of(
                      new ValidationError(
                          id.isEmpty() ? "job_id" : "cursor",
                          "invalid_format",
                          "not a valid job id, cursor or limit"))),
              correlation));
    }
    return batches
        .status(principal(request), id.get(), cursor, limit)
        .map(
            status ->
                ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsBytes(status)))
        .orElseGet(() -> notFound(correlation));
  }

  /**
   * {@code GET /api/v1/decisions/{transaction_id}} (D-14).
   *
   * @param request the request
   * @param transactionId the transaction
   * @return the latest decision state or 404
   */
  @GetMapping("/api/v1/decisions/{transaction_id}")
  public ResponseEntity<byte[]> decision(
      HttpServletRequest request, @PathVariable("transaction_id") String transactionId) {
    UUID correlation = ApiKeyFilter.correlation(request);
    Optional<ResponseEntity<byte[]>> refused = scope(request, ApiScope.DECISIONS_READ);
    if (refused.isPresent()) {
      return refused.get();
    }
    Optional<UUID> id = uuid(transactionId);
    if (id.isEmpty()) {
      return notFound(correlation);
    }
    Optional<DecisionState> state = states.latest(principal(request).institutionId(), id.get());
    return state
        .map(
            s ->
                ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsBytes(FactCodec.finalDecision(s))))
        .orElseGet(() -> notFound(correlation));
  }

  private static Optional<ResponseEntity<byte[]>> refuse(
      HttpServletRequest request, ApiScope scope, int maxBody) {
    Optional<ResponseEntity<byte[]>> denied = scope(request, scope);
    if (denied.isPresent()) {
      return denied;
    }
    UUID correlation = ApiKeyFilter.correlation(request);
    String type = request.getContentType();
    if (type == null
        || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) {
      return Optional.of(
          problem(
              Problems.problem(
                  "unsupported-media-type",
                  "Unsupported media type",
                  415,
                  "Content-Type must be application/json",
                  correlation)));
    }
    if (request.getContentLengthLong() > maxBody) {
      return Optional.of(tooLarge(maxBody, correlation));
    }
    return Optional.empty();
  }

  private static ResponseEntity<byte[]> tooLarge(int maxBody, UUID correlation) {
    return problem(
        Problems.problem(
            "payload-too-large",
            "Payload too large",
            413,
            "the body exceeds " + maxBody + " bytes",
            correlation));
  }

  private static Optional<ResponseEntity<byte[]>> scope(
      HttpServletRequest request, ApiScope scope) {
    if (principal(request) == null) {
      // A path variant that reached this controller without passing the filter: unauthenticated,
      // not a 500 (Principal Review finding 13).
      return Optional.of(
          problem(
              Problems.problem(
                  "unauthorized",
                  "Unauthorized",
                  401,
                  "an API key is required",
                  ApiKeyFilter.correlation(request))));
    }
    if (!principal(request).has(scope)) {
      return Optional.of(
          problem(
              Problems.problem(
                  "forbidden",
                  "Forbidden",
                  403,
                  "the API key lacks the " + scope.wireName() + " scope",
                  ApiKeyFilter.correlation(request))));
    }
    return Optional.empty();
  }

  private static ApiPrincipal principal(HttpServletRequest request) {
    return (ApiPrincipal) request.getAttribute(ApiKeyFilter.PRINCIPAL);
  }

  private static Optional<UUID> uuid(String value) {
    if (value == null
        || !value.matches(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(value));
  }

  private static ResponseEntity<byte[]> notFound(UUID correlation) {
    return problem(
        Problems.problem(
            "not-found", "Not found", 404, "not found in your institution", correlation));
  }

  private static ResponseEntity<byte[]> unavailable(UUID correlation) {
    return ResponseEntity.status(503)
        .header(HttpHeaders.RETRY_AFTER, "1")
        .contentType(MediaType.parseMediaType(Problems.MEDIA_TYPE))
        .body(
            JSON.writeValueAsBytes(
                Problems.problem(
                    "service-unavailable",
                    "Temporarily unavailable",
                    503,
                    "retry the same request",
                    correlation)));
  }

  private static ResponseEntity<byte[]> problem(ObjectNode problem) {
    return ResponseEntity.status(problem.get("status").asInt())
        .contentType(MediaType.parseMediaType(Problems.MEDIA_TYPE))
        .body(JSON.writeValueAsBytes(problem));
  }
}
