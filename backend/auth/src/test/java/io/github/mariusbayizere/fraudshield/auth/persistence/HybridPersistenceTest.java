package io.github.mariusbayizere.fraudshield.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.auth.testing.JpaTestStack;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The JPA half of the hybrid persistence layer against the real schema (ADR 0071): the mappings
 * validate against Flyway's schema, row-level security holds for Hibernate's statements through the
 * per-transaction tenant setting, and {@code users.version} is a working JPA optimistic lock.
 */
@Tag("requires-docker")
@Tag("FR-06-02")
class HybridPersistenceTest {

  private static TestDatabase db;
  private static JpaTestStack app;
  private static UUID bankA;
  private static UUID bankB;

  @BeforeAll
  static void start() {
    db = TestDatabase.create();
    app = JpaTestStack.of(db, "fs_app");
    bankA = db.createInstitution("jpa-a");
    bankB = db.createInstitution("jpa-b");
  }

  private static StaffAccount account(UUID institution) {
    UUID id = UUID.randomUUID();
    return new StaffAccount(
        id,
        institution,
        "Amani",
        "Uwase",
        "jpa." + id + "@bank.rw",
        null,
        StaffRole.ANALYST,
        null,
        AccountStatus.ACTIVE,
        null,
        0,
        0,
        true,
        StaffLocale.en,
        null,
        false,
        ("J" + id.toString().replace("-", "")).substring(0, 16),
        Department.IT,
        null,
        Instant.now(),
        0);
  }

  private static UUID insert(UUID institution) {
    StaffAccount account = account(institution);
    app.tenants()
        .runInTenant(
            institution,
            () ->
                app.entities()
                    .persist(
                        StaffUserEntity.create(
                            account,
                            "$2a$12$abcdefghijklmnopqrstuuv0123456789abcdefghijklmnopqrst",
                            null)));
    return account.id();
  }

  @Test
  void jpaQueriesAreTenantIsolatedAndFailClosedWithoutTenant() {
    UUID inA = insert(bankA);
    UUID inB = insert(bankB);
    assertThat(app.tenants().inTenant(bankA, () -> app.users().findById(inA))).isPresent();
    assertThat(app.tenants().inTenant(bankA, () -> app.users().findById(inB)))
        .as("another institution's row is invisible to Hibernate too")
        .isEmpty();
    assertThat(app.tenants().inTenant(bankA, () -> app.users().findAll()))
        .extracting(StaffUserEntity::getId)
        .contains(inA)
        .doesNotContain(inB);
    Long withoutTenant =
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.orm.jpa.JpaTransactionManager(
                    app.entities().getEntityManagerFactory()))
            .execute(status -> app.users().count());
    assertThat(withoutTenant).as("no tenant set: fails closed").isZero();
    assertThatThrownBy(
            () ->
                app.tenants()
                    .runInTenant(
                        bankA,
                        () ->
                            app.entities()
                                .persist(StaffUserEntity.create(account(bankB), null, "g-1"))))
        .as("a row for another institution cannot be written")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void versionIsJpaOptimisticLock() {
    UUID id = insert(bankA);
    StaffUserEntity stale =
        app.tenants().inTenant(bankA, () -> app.users().findById(id).orElseThrow());
    long before = stale.getVersion();
    app.tenants()
        .runInTenant(
            bankA,
            () -> {
              StaffUserEntity fresh = app.users().findById(id).orElseThrow();
              fresh.edit(
                  "Aline",
                  "Uwase",
                  null,
                  Department.IT,
                  StaffRole.ANALYST,
                  AccountStatus.ACTIVE,
                  StaffLocale.en,
                  null);
            });
    long after =
        app.tenants().inTenant(bankA, () -> app.users().findById(id).orElseThrow().getVersion());
    assertThat(after).as("an entity update advances @Version").isEqualTo(before + 1);
    stale.edit(
        "Stale",
        "Write",
        null,
        Department.IT,
        StaffRole.ADMIN,
        AccountStatus.ACTIVE,
        StaffLocale.en,
        null);
    assertThatThrownBy(() -> app.tenants().runInTenant(bankA, () -> app.users().save(stale)))
        .as("a write based on an old version is refused")
        .isInstanceOfAny(
            ObjectOptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class);
    assertThat(
            app.tenants().inTenant(bankA, () -> app.users().findById(id).orElseThrow().getRole()))
        .isEqualTo(StaffRole.ANALYST);
  }

  @Test
  void signInBookkeepingDoesNotAdvanceTheVersion() {
    UUID id = insert(bankA);
    long before =
        app.tenants().inTenant(bankA, () -> app.users().findById(id).orElseThrow().getVersion());
    app.tenants()
        .runInTenant(
            bankA,
            () -> {
              app.users().incrementFailures(id);
              app.users().recordLoginSuccess(id, Instant.now());
              app.users().bumpTokenVersion(id);
            });
    assertThat(
            app.tenants()
                .inTenant(bankA, () -> app.users().findById(id).orElseThrow().getVersion()))
        .as("a sign-in never makes an administrator's edit stale")
        .isEqualTo(before);
  }

  @Test
  void schemaValidationRefusesMappingsThatDoNotMatchFlyway() throws Exception {
    TestDatabase drifted = TestDatabase.create();
    try (Connection admin = drifted.superuser();
        Statement statement = admin.createStatement()) {
      statement.execute("ALTER TABLE fraudshield.users DROP COLUMN version");
    }
    assertThatThrownBy(() -> JpaTestStack.of(drifted, "fs_app"))
        .as("ddl-auto=validate: Hibernate checks, never alters")
        .hasRootCauseInstanceOf(org.hibernate.tool.schema.spi.SchemaManagementException.class);
    try (Connection admin = drifted.superuser();
        Statement statement = admin.createStatement()) {
      var rows =
          statement.executeQuery(
              "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fraudshield'"
                  + " AND table_name = 'users' AND column_name = 'version'");
      rows.next();
      assertThat(rows.getInt(1)).as("and it did not add the column back").isZero();
    }
  }
}
