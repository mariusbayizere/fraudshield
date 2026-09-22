package io.github.mariusbayizere.fraudshield.auth.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository of {@code office_ip_allowlist} (ADR 0071). */
public interface OfficeIpRangeJpaRepository extends JpaRepository<OfficeIpRangeEntity, UUID> {

  /**
   * Ranges with their creators in one query (no N + 1), newest first.
   *
   * @return the ranges
   */
  @EntityGraph(attributePaths = "createdBy")
  @Query("select r from OfficeIpRangeEntity r order by r.createdAt desc, r.id desc")
  List<OfficeIpRangeEntity> findAllWithCreator();

  /**
   * The networks only, for the sign-in ceiling check.
   *
   * @return the CIDRs
   */
  @Query("select r.cidr from OfficeIpRangeEntity r")
  List<String> findAllCidrs();
}
