package io.github.mariusbayizere.fraudshield.ingest.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Batch jobs (ADR 0068). Row-level security scopes every statement to the institution set on the
 * transaction, so a job of another institution is invisible rather than forbidden.
 */
public interface BatchJobRepository extends Repository<BatchJobEntity, UUID> {

  /**
   * Saves a new or changed job.
   *
   * @param job the job
   * @return the managed job
   */
  BatchJobEntity save(BatchJobEntity job);

  /**
   * One job of this institution.
   *
   * @param id the job
   * @return the job, or empty when it is not this institution's
   */
  Optional<BatchJobEntity> findById(UUID id);
}
