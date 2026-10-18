package io.github.mariusbayizere.fraudshield.persistence.schema;

import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Staff users, for the demo seeder only (ADR 0068 point 4). M7's staff module owns this table; at
 * merge this repository and {@link DemoUserEntity} give way to it.
 */
public interface DemoUserRepository extends Repository<DemoUserEntity, UUID> {

  /**
   * Saves a user.
   *
   * @param user the user
   * @return the managed user
   */
  DemoUserEntity save(DemoUserEntity user);
}
