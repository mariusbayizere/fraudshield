package io.github.mariusbayizere.fraudshield.auth.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository of {@code api_keys} for the administrator lifecycle (ADR 0071). */
public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, UUID> {

  /**
   * The row ID of a key of the institution. A scalar, so it never meets the persistence context;
   * the caller locks the row by ID ({@code ApiKeyRepository.findForUpdate}).
   *
   * @param keyId public key ID
   * @return the row ID
   */
  @Query("select k.id from ApiKeyEntity k where k.keyId = :keyId")
  Optional<UUID> findIdByKeyId(@Param("keyId") String keyId);

  /**
   * Keys of the institution, newest first.
   *
   * @return the keys
   */
  List<ApiKeyEntity> findAllByOrderByCreatedAtDescIdDesc();

  /**
   * Whether a live key has this name.
   *
   * @param name name
   * @param state the revoked state
   * @return whether one exists
   */
  boolean existsByNameAndStateNot(String name, String state);

  /**
   * Records use of a key (throttled by the caller; no entity is loaded on the hot path).
   *
   * @param id key
   * @param at time of use
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update ApiKeyEntity k set k.lastUsedAt = :at where k.id = :id")
  int touch(@Param("id") UUID id, @Param("at") Instant at);
}
