package io.github.mariusbayizere.fraudshield.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * No N + 1 queries on the listing endpoints, counted with Hibernate statistics (ADR 0071): the
 * number of statements must not grow with the number of rows listed. The running application also
 * uses the settings ADR 0071 requires.
 */
@Tag("FR-06-01")
class QueryCountTest extends AuthIntegrationTest {

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private Environment environment;

  @Autowired private PlatformTransactionManager transactionManager;

  private Statistics statistics() {
    return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
  }

  /**
   * Statements one call issues. A first call warms the session-state cache, so the count covers the
   * endpoint's own queries; lazy entity or collection fetches, the signature of N + 1, must be
   * zero.
   */
  private long statementsFor(String path, Session session) {
    assertThat(http.get(path, session.bearer()).status()).as(path).isEqualTo(200);
    Statistics statistics = statistics();
    statistics.clear();
    Http.Response response = http.get(path, session.bearer());
    assertThat(response.status()).as(path).isEqualTo(200);
    assertThat(statistics.getEntityFetchCount()).as("lazy entity fetches on %s", path).isZero();
    assertThat(statistics.getCollectionFetchCount()).as("collection fetches on %s", path).isZero();
    return statistics.getPrepareStatementCount();
  }

  private static void addRange(UUID institution, UUID creator, String cidr) {
    superuser(
        "INSERT INTO fraudshield.office_ip_allowlist"
            + " (institution_id, cidr, description, created_by)"
            + " VALUES (?, ?::cidr, 'Test office', ?)",
        institution,
        cidr,
        creator);
  }

  @Test
  void applicationRunsWithValidateAndWithoutOpenInView() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
    assertThat(statistics().isStatisticsEnabled()).isTrue();
  }

  @Test
  void listingOfficeRangesWithTheirCreatorsIsOneQueryWhateverTheCount() {
    UUID institution = DB.createInstitution("n1-net-" + System.nanoTime() % 100000);
    Account admin = createAccount(institution, "ADMIN", "ACTIVE");
    Session session = issueSession(admin);
    addRange(institution, admin.id(), "10.1.0.0/24");
    long one = statementsFor("/api/v1/admin/ip-allowlist", session);
    for (int i = 2; i <= 12; i++) {
      Account creator = createAccount(institution, "ADMIN", "ACTIVE");
      addRange(institution, creator.id(), "10." + i + ".0.0/24");
    }
    long twelve = statementsFor("/api/v1/admin/ip-allowlist", session);
    assertThat(one).as("1 range").isEqualTo(1);
    assertThat(twelve).as("12 ranges by 12 different creators").isEqualTo(one);
  }

  @Test
  void listingUsersApprovalsAndKeysIsOneQueryWhateverTheCount() {
    UUID institution = DB.createInstitution("n1-users-" + System.nanoTime() % 100000);
    Account admin = createAccount(institution, "ADMIN", "ACTIVE");
    Session session = issueSession(admin);
    final long usersFew = statementsFor("/api/v1/admin/users?limit=200", session);
    final long approvalsFew = statementsFor("/api/v1/admin/approvals", session);
    final long keysFew = statementsFor("/api/v1/admin/api-keys", session);
    for (int i = 0; i < 15; i++) {
      createAccount(institution, "ANALYST", "ACTIVE");
      createAccount(institution, "ANALYST", "PENDING_APPROVAL");
    }
    assertThat(statementsFor("/api/v1/admin/users?limit=200", session))
        .isEqualTo(usersFew)
        .isEqualTo(1);
    assertThat(statementsFor("/api/v1/admin/approvals", session))
        .isEqualTo(approvalsFew)
        .isEqualTo(1);
    assertThat(statementsFor("/api/v1/admin/api-keys", session)).isEqualTo(keysFew).isEqualTo(1);
  }
}
