package io.github.mariusbayizere.fraudshield.notify.verification;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessage;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessages;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.PostgresSink;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolRecord;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionTransitionService;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.notify.kafka.MalformedPayloadException;
import io.github.mariusbayizere.fraudshield.notify.kafka.NotTheKeptDecisionException;
import io.github.mariusbayizere.fraudshield.notify.kafka.NotYetRecordedException;
import io.github.mariusbayizere.fraudshield.notify.sms.ContactDirectory;
import io.github.mariusbayizere.fraudshield.notify.sms.CustomerSmsSender;
import io.github.mariusbayizere.fraudshield.notify.sms.InstitutionMessaging;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsCatalogue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The SMS consumer's decision table against the real writer (PostgresSink) and the real renderer
 * (KafkaMessages): docs/architecture/decision-fact-ordering.md, sections 4 and 5, invariants I1 to
 * I5, I9, I13, I15 and I16. A transaction is decided twice the way production can: the same
 * submission (a crash before the response, then a retry) or a different body for the same id, while
 * PostgreSQL keeps the first decision and Kafka carries both.
 */
@Tag("requires-docker")
@Tag("FR-03-04")
@Tag("D-25")
class SmsOrderingTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OTHER_ACCOUNT = "tok_AcctOtherZzzzYyyyXxxx01";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final MutableClock clock = new MutableClock(NOW);
  private final InMemoryPorts ports = new InMemoryPorts();
  private final List<String> sent = new CopyOnWriteArrayList<>();
  private final List<String> phones = new CopyOnWriteArrayList<>();
  private TestDatabase db;
  private DecisionService decisions;
  private PostgresSink sink;
  private CustomerSmsSender sender;
  private long spooled;

  @BeforeEach
  void start() throws Exception {
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    decisions =
        new DecisionService(
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            DecisionMetrics.NONE,
            DecisionSettings.DEFAULTS,
            clock);
    sink = new PostgresSink(db.dataSource("fs_app"), Files.createTempDirectory("dead"));
    sender = sender(db.dataSource("fs_app"));
  }

  private CustomerSmsSender sender(DataSource dataSource) {
    return new CustomerSmsSender(
        dataSource,
        (institution, account) ->
            Optional.of(
                new ContactDirectory.Contact(
                    account.equals(OTHER_ACCOUNT) ? "+250788000002" : "+250788000001",
                    "en",
                    "***" + account.substring(account.length() - 4))),
        institution ->
            Optional.of(
                new InstitutionMessaging.Settings(
                    "FSBANK", "+250788100100", "https://verify.fsbank.rw/v/")),
        new VerificationService(
            dataSource, new DecisionTransitionService(ports, ports, ports, clock), clock),
        (senderId, phone, text) -> {
          sent.add(text);
          phones.add(phone);
          return "ATXid_" + sent.size();
        },
        new SmsCatalogue(),
        clock);
  }

  /** The intent the test double's policy composes, with self-service allowed or not. */
  static DecisionEvent.CustomerNotificationRequested withLink(
      DecisionEvent.CustomerNotificationRequested n, boolean allowed) {
    return new DecisionEvent.CustomerNotificationRequested(
        n.notificationId(),
        n.institutionId(),
        n.accountToken(),
        n.autoBlockEventId(),
        n.templateKey(),
        n.locale(),
        allowed,
        n.maskedAccount(),
        n.amount(),
        n.localTime(),
        n.referenceCode(),
        n.requestedAt());
  }

  private static byte[] fingerprint() {
    byte[] f = new byte[32];
    RANDOM.nextBytes(f);
    return f;
  }

  /** Decides once and returns that decision's facts: one spool record's worth. */
  private List<DecisionEvent> decide(Transaction t, byte[] fingerprint, double score) {
    ports.scores = x -> Optional.of(score);
    int before = ports.events.size();
    decisions.decide(t, fingerprint, System.nanoTime());
    return List.copyOf(ports.events.subList(before, ports.events.size()));
  }

  private void persist(List<DecisionEvent> facts) throws Exception {
    persist(sink, facts);
  }

  private void persist(PostgresSink writer, List<DecisionEvent> facts) throws Exception {
    long offset = spooled++;
    writer.accept(List.of(new SpoolRecord(offset, offset + 1, FactCodec.encode(facts))));
  }

  private static KafkaMessage intentOf(List<DecisionEvent> facts) {
    return new KafkaMessages("fraudshield-api", 0)
        .render(facts).stream()
            .filter(m -> m.topic().equals("fs.notifications.customer"))
            .findFirst()
            .orElseThrow();
  }

  private static JsonNode payload(KafkaMessage intent) {
    return JSON.readTree(intent.value()).get("payload");
  }

  private CustomerSmsSender.Outcome send(KafkaMessage intent) throws Exception {
    return send(sender, payload(intent), intent);
  }

  private static CustomerSmsSender.Outcome send(
      CustomerSmsSender sender, JsonNode payload, KafkaMessage intent) throws Exception {
    Optional<UUID> transaction =
        Optional.ofNullable(intent.headers().get(KafkaMessage.TRANSACTION_ID_HEADER))
            .map(UUID::fromString);
    return sender.send(INSTITUTION, payload, transaction);
  }

  private String one(String sql) throws Exception {
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      return r.next() ? r.getString(1) : null;
    }
  }

  private void nothingDoneFor(KafkaMessage intent) throws Exception {
    assertThat(sent).isEmpty();
    String block = payload(intent).get("auto_block_event_id").asString();
    assertThat(
            one(
                "SELECT count(*) FROM fraudshield.customer_verifications WHERE"
                    + " auto_block_event_id = '"
                    + block
                    + "'"))
        .isEqualTo("0");
    assertThat(
            one(
                "SELECT count(*) FROM fraudshield.customer_notifications WHERE notification_id"
                    + " = '"
                    + payload(intent).get("notification_id").asString()
                    + "' AND event <> 'REQUESTED'"))
        .isEqualTo("0");
  }

  @Test
  void theIntentCarriesItsTransactionAndIdsDerivedFromTheSubmission() {
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    byte[] f = fingerprint();
    KafkaMessage first = intentOf(decide(t, f, 0.9));
    KafkaMessage again = intentOf(decide(t, f, 0.95));
    KafkaMessage otherBody = intentOf(decide(t, fingerprint(), 0.9));
    assertThat(first.headers())
        .containsEntry(KafkaMessage.TRANSACTION_ID_HEADER, t.transactionId().toString());
    assertThat(payload(again).get("auto_block_event_id"))
        .isEqualTo(payload(first).get("auto_block_event_id"));
    assertThat(payload(again).get("notification_id"))
        .isEqualTo(payload(first).get("notification_id"));
    assertThat(again.eventId()).isEqualTo(first.eventId());
    assertThat(payload(otherBody).get("auto_block_event_id"))
        .isNotEqualTo(payload(first).get("auto_block_event_id"));
  }

  @Test
  void keptDecisionWithoutBlockThenBlockIsDeadLetteredAtOnceAndNeverSent() throws Exception {
    // I3a, the ordering the owner named: the kept decision is written and did not block.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    byte[] f = fingerprint();
    List<DecisionEvent> kept = decide(t, f, 0.1);
    assertThat(kept).noneMatch(DecisionEvent.AutoBlocked.class::isInstance);
    List<DecisionEvent> second = decide(t, f, 0.9);
    persist(kept);
    persist(second);
    KafkaMessage intent = intentOf(second);
    assertThatThrownBy(() -> send(intent))
        .isInstanceOf(NotTheKeptDecisionException.class)
        .hasMessageContaining(t.transactionId().toString());
    nothingDoneFor(intent);
  }

  @Test
  void keptDecisionWithoutBlockReadBeforeItIsWrittenWaitsThenIsDeadLettered() throws Exception {
    // I3b: the intent overtakes the kept decision: S4 first, S3 once it is written.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    byte[] f = fingerprint();
    List<DecisionEvent> kept = decide(t, f, 0.1);
    KafkaMessage intent = intentOf(decide(t, f, 0.9));
    assertThatThrownBy(() -> send(intent)).isInstanceOf(NotYetRecordedException.class);
    persist(kept);
    assertThatThrownBy(() -> send(intent)).isInstanceOf(NotTheKeptDecisionException.class);
    nothingDoneFor(intent);
  }

  @Test
  void theSameSubmissionBlockingTwiceSendsOneSms() throws Exception {
    // I4 and I2: derived ids make the second intent the same notification.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    byte[] f = fingerprint();
    List<DecisionEvent> first = decide(t, f, 0.9);
    List<DecisionEvent> second = decide(t, f, 0.95);
    persist(first);
    persist(second);
    assertThat(send(intentOf(second))).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(send(intentOf(first))).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(send(intentOf(second))).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(sent).hasSize(1);
  }

  @Test
  void differentBodyForTheSameTransactionNeverReachesTheKeptAccount() throws Exception {
    // I4b (contract review 2, MAJOR 1): another account's body for the same transaction id.
    UUID id = UUID.randomUUID();
    List<DecisionEvent> kept =
        decide(
            Fixtures.transaction(id, Fixtures.ACCOUNT, "15000", Channel.CARD), fingerprint(), 0.9);
    List<DecisionEvent> other =
        decide(Fixtures.transaction(id, OTHER_ACCOUNT, "15000", Channel.CARD), fingerprint(), 0.9);
    persist(kept);
    persist(other);
    KafkaMessage otherIntent = intentOf(other);
    assertThatThrownBy(() -> send(otherIntent)).isInstanceOf(NotTheKeptDecisionException.class);
    nothingDoneFor(otherIntent);
    assertThat(send(intentOf(kept))).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(phones).containsExactly("+250788000001");
  }

  @Test
  void theLinkIsTheKeptDecisionsAndFailsClosedWithoutItsRequestedRow() throws Exception {
    // I5: the kept decision refused self-service; the intent says otherwise.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    List<DecisionEvent> kept = decide(t, fingerprint(), 0.9);
    persist(kept);
    KafkaMessage intent = intentOf(kept);
    ObjectNode claimsLink = (ObjectNode) payload(intent);
    claimsLink.put("verification_link_allowed", true);
    assertThat(send(sender, claimsLink, intent)).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(sent.getLast())
        .as("without self-service the SMS has no link and says whom to call")
        .doesNotContain("https://")
        .contains("+250788100100");
    assertThat(one("SELECT count(*) FROM fraudshield.customer_verifications")).isEqualTo("0");
    assertThat(
            one(
                "SELECT verification_link_allowed FROM fraudshield.customer_notifications WHERE"
                    + " event = 'SENT'"))
        .isEqualTo("f");

    // A block without its REQUESTED row (G2 rules it out): no link either.
    Transaction u =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    List<DecisionEvent> facts = decide(u, fingerprint(), 0.9);
    persist(
        facts.stream()
            .filter(e -> !(e instanceof DecisionEvent.CustomerNotificationRequested))
            .toList());
    KafkaMessage without =
        intentOf(
            facts.stream()
                .map(
                    e ->
                        e instanceof DecisionEvent.CustomerNotificationRequested n
                            ? withLink(n, true)
                            : e)
                .toList());
    assertThat(send(without)).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(sent.getLast()).doesNotContain("https://");
  }

  private static UUID block(List<DecisionEvent> facts) {
    // The auto-block of one decision's facts.
    return facts.stream()
        .filter(DecisionEvent.AutoBlocked.class::isInstance)
        .map(e -> ((DecisionEvent.AutoBlocked) e).autoBlockEventId())
        .findFirst()
        .orElseThrow();
  }

  @Test
  void blockResolvedBeforeItsSmsIsNotSent() throws Exception {
    // I13, S2r.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    List<DecisionEvent> kept = decide(t, fingerprint(), 0.9);
    persist(kept);
    UUID analyst = UUID.randomUUID();
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.users (id, institution_id, first_name,"
              + " last_name, email, role, employee_id, department, password_hash)"
              + " VALUES (?, ?, 'Test', 'Officer', ?, 'RISK_OFFICER', ?,"
              + " 'RISK', '$2b$12$notARealHashJustTheShapeOfOne.............')",
          analyst,
          INSTITUTION,
          analyst + "@example.test",
          "EMP" + analyst.toString().substring(0, 5));
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.unblock_events (institution_id, auto_block_event_id, cause,"
              + " actor_user_id) VALUES (?, ?, 'ANALYST', ?)",
          INSTITUTION,
          block(kept),
          analyst);
    }
    assertThat(send(intentOf(kept))).isEqualTo(CustomerSmsSender.Outcome.RESOLVED);
    assertThat(sent).isEmpty();
    assertThat(sender.resolvedBeforeSend()).as("S2r is counted").isEqualTo(1);
  }

  @Test
  void intentForAnotherAccountsBlockIsNeverSent() throws Exception {
    // I16, S0: a crafted intent; derived ids make it impossible in production.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    List<DecisionEvent> kept = decide(t, fingerprint(), 0.9);
    persist(kept);
    KafkaMessage intent = intentOf(kept);
    ObjectNode crafted = (ObjectNode) payload(intent);
    crafted.put("account_token", OTHER_ACCOUNT);
    assertThatThrownBy(() -> send(sender, crafted, intent))
        .isInstanceOf(NotTheKeptDecisionException.class);
    assertThat(sent).isEmpty();
  }

  @Test
  void headerlessIntentWaitsRatherThanBeingJudgedPermanent() throws Exception {
    // I15: an intent on the topic before deployment carries no transaction, so it can only wait.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    byte[] f = fingerprint();
    persist(decide(t, f, 0.1));
    KafkaMessage intent = intentOf(decide(t, f, 0.9));
    assertThatThrownBy(() -> sender.send(INSTITUTION, payload(intent)))
        .isInstanceOf(NotYetRecordedException.class);
  }

  @Test
  void intentReadWhileItsDecisionIsBeingWrittenWaitsAndIsNeverJudgedPermanent() throws Exception {
    // I9: PostgresSink's transaction is held open at its commit; the classification is one
    // statement, so it sees neither the kept decision nor the block, never one without the other.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    List<DecisionEvent> kept = decide(t, fingerprint(), 0.9);
    CountDownLatch atCommit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    PostgresSink held =
        new PostgresSink(
            holdingCommits(db.dataSource("fs_app"), atCommit, release),
            Files.createTempDirectory("dead"));
    final Thread writer =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    persist(held, kept);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertThat(atCommit.await(30, TimeUnit.SECONDS)).isTrue();
    KafkaMessage intent = intentOf(kept);
    assertThatThrownBy(() -> send(intent)).isInstanceOf(NotYetRecordedException.class);
    release.countDown();
    writer.join(Duration.ofSeconds(30).toMillis());
    assertThat(send(intent)).isEqualTo(CustomerSmsSender.Outcome.SENT);
  }

  @Test
  void theClassificationIsOneStatementOnOneConnection() throws Exception {
    // I9, structurally (the implementation review of bd48557): classifying an intent that must
    // not be sent opens one connection and prepares one statement besides the tenant's, so the
    // facts it reads come from one snapshot. Splitting the classification would fail this.
    Transaction t =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000", Channel.CARD);
    byte[] f = fingerprint();
    persist(decide(t, f, 0.1));
    KafkaMessage intent = intentOf(decide(t, f, 0.9));
    List<String> statements = new CopyOnWriteArrayList<>();
    java.util.concurrent.atomic.AtomicInteger connections =
        new java.util.concurrent.atomic.AtomicInteger();
    CustomerSmsSender counted = sender(recording(db.dataSource("fs_app"), connections, statements));
    assertThatThrownBy(() -> send(counted, payload(intent), intent))
        .isInstanceOf(NotTheKeptDecisionException.class);
    assertThat(connections.get()).isEqualTo(1);
    assertThat(statements).hasSize(2);
    assertThat(statements.getFirst()).contains("set_config");
    assertThat(statements.getLast())
        .contains("auto_block_events", "customer_notifications", "decision_states");
  }

  @Test
  void anIntentWithoutReadableIdsIsMalformedNotRetriedForEver() {
    // The implementation review of bd48557: a payload the sender cannot read is dead-lettered.
    ObjectNode broken = JSON.createObjectNode();
    broken.put("notification_id", "not-a-uuid");
    assertThatThrownBy(() -> sender.send(INSTITUTION, broken))
        .isInstanceOf(MalformedPayloadException.class);
    assertThat(sent).isEmpty();
  }

  /** A data source that records the connections it lends and the statements they prepare. */
  private static DataSource recording(
      DataSource target,
      java.util.concurrent.atomic.AtomicInteger connections,
      List<String> statements) {
    return (DataSource)
        Proxy.newProxyInstance(
            SmsOrderingTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (!method.getName().equals("getConnection")) {
                return result;
              }
              connections.incrementAndGet();
              Connection connection = (Connection) result;
              return Proxy.newProxyInstance(
                  SmsOrderingTest.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (p, m, a) -> {
                    if (m.getName().equals("prepareStatement")) {
                      statements.add((String) a[0]);
                    }
                    return invoke(connection, m, a);
                  });
            });
  }

  /** A data source whose connections stop at commit until released. */
  private static DataSource holdingCommits(
      DataSource target, CountDownLatch atCommit, CountDownLatch release) {
    return (DataSource)
        Proxy.newProxyInstance(
            SmsOrderingTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (!method.getName().equals("getConnection")) {
                return result;
              }
              Connection connection = (Connection) result;
              return Proxy.newProxyInstance(
                  SmsOrderingTest.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (p, m, a) -> {
                    if (m.getName().equals("commit")) {
                      atCommit.countDown();
                      release.await(30, TimeUnit.SECONDS);
                    }
                    return invoke(connection, m, a);
                  });
            });
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
