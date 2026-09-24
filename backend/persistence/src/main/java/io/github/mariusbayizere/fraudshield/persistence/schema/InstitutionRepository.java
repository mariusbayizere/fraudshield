package io.github.mariusbayizere.fraudshield.persistence.schema;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Institutions (ADR 0068). The demo seeder is M6's only writer. */
public interface InstitutionRepository extends Repository<InstitutionEntity, UUID> {

  /**
   * Saves an institution.
   *
   * @param institution the institution
   * @return the managed institution
   */
  InstitutionEntity save(InstitutionEntity institution);

  /**
   * Finds one by its code, which is how the seeder knows whether it has already run.
   *
   * @param code the code
   * @return the institution, if it exists
   */
  Optional<InstitutionEntity> findByCode(String code);
}
