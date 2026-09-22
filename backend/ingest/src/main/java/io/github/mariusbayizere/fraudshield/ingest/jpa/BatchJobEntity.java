package io.github.mariusbayizere.fraudshield.ingest.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A batch ingestion job (V3 {@code batch_jobs}, FR-01-06).
 *
 * <p>The one mutable CRUD table the decision path owns, so under ADR 0068 it is JPA: a job is
 * created, moves through its states and is read by the client polling it. Its items are a separate,
 * append-only table and stay explicit SQL, as does the cross-institution sweep that fails jobs an
 * earlier process left behind.
 *
 * <p>{@link DynamicUpdate} so a flush writes only the columns that changed, and {@code updated_at}
 * is left to the database trigger that has always maintained it.
 */
@Entity
@DynamicUpdate
@Table(name = "batch_jobs")
public class BatchJobEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "institution_id", nullable = false, updatable = false)
  private UUID institutionId;

  @Column(name = "api_key_id", nullable = false, updatable = false)
  private UUID apiKeyId;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "total", nullable = false, updatable = false)
  private int total;

  @Column(name = "processed", nullable = false)
  private int processed;

  @Column(name = "failed", nullable = false)
  private int failed;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  /** For Hibernate. */
  protected BatchJobEntity() {}

  /**
   * A queued job.
   *
   * @param id the job
   * @param institutionId institution
   * @param apiKeyId the key that submitted it
   * @param total items in the batch
   */
  public BatchJobEntity(UUID id, UUID institutionId, UUID apiKeyId, int total) {
    this.id = id;
    this.institutionId = institutionId;
    this.apiKeyId = apiKeyId;
    this.total = total;
    this.state = "QUEUED";
  }

  /**
   * Records progress or the end of the job.
   *
   * @param state QUEUED, RUNNING, COMPLETED or FAILED
   * @param processed items decided so far
   * @param failed items rejected so far
   * @param completedAt when it ended, or null while it runs
   */
  public void progress(String state, int processed, int failed, Instant completedAt) {
    this.state = state;
    this.processed = processed;
    this.failed = failed;
    this.completedAt = completedAt;
  }

  /**
   * The job's state.
   *
   * @return the state
   */
  public String state() {
    return state;
  }

  /**
   * Items in the batch.
   *
   * @return the total
   */
  public int total() {
    return total;
  }

  /**
   * Items decided.
   *
   * @return the count
   */
  public int processed() {
    return processed;
  }

  /**
   * Items rejected.
   *
   * @return the count
   */
  public int failed() {
    return failed;
  }
}
