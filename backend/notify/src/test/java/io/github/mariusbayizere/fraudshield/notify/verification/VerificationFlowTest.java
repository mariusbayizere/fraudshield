package io.github.mariusbayizere.fraudshield.notify.verification;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
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
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.notify.sms.AfricasTalkingGateway;
import io.github.mariusbayizere.fraudshield.notify.sms.ContactDirectory;
import io.github.mariusbayizere.fraudshield.notify.sms.CustomerSmsSender;
import io.github.mariusbayizere.fraudshield.notify.sms.Gsm7;
import io.github.mariusbayizere.fraudshield.notify.sms.InstitutionMessaging;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsCatalogue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-03-04, FR-03-05, E.7 and D-25 end to end from an auto-block: the intent becomes one GSM-7 SMS
 * with a single-use link, the link expires at exactly 10 minutes and answers once, "yes" lifts the
 * block with a LEGITIMATE label and "no" labels fraud.
 */
@Tag("requires-docker")
@Tag("FR-03-04")
@Tag("FR-03-05")
@Tag("D-25")
class VerificationFlowTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final MutableClock clock = new MutableClock(NOW);
  private final InMemoryPorts ports = new InMemoryPorts();
  private final List<String> sent = new CopyOnWriteArrayList<>();
  private TestDatabase db;
  private VerificationService verifications;
  private CustomerSmsSender sender;
  private JsonNode intent;
  private UUID blockedTransaction;

  /** The intent is composed with self-service allowed, as the test double's policy does not. */
  @BeforeEach
  void blockOneTransaction() throws Exception {
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    ports.scores = t -> Optional.of(0.9);
    DecisionService decisions =
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
    decisions.decide(Fixtures.transaction("15000"), new byte[32], System.nanoTime());
    List<SpoolRecord> records = new ArrayList<>();
    for (DecisionEvent fact : ports.events) {
      byte[] payload = FactCodec.encode(List.of(fact));
      records.add(new SpoolRecord(records.size(), records.size() + 1, payload));
    }
    new PostgresSink(db.dataSource("fs_app"), Files.createTempDirectory("dead")).accept(records);
    blockedTransaction = ports.eventsOf(DecisionEvent.AutoBlocked.class).getFirst().transactionId();
    KafkaMessage message =
        new KafkaMessages("fraudshield-api", 0)
            .render(ports.events).stream()
                .filter(m -> m.topic().equals("fs.notifications.customer"))
                .findFirst()
                .orElseThrow();
    intent = JSON.readTree(message.value()).get("payload");
    ((tools.jackson.databind.node.ObjectNode) intent).put("verification_link_allowed", true);

    DecisionTransitionService transitions =
        new DecisionTransitionService(ports, ports, ports, clock);
    verifications = new VerificationService(db.dataSource("fs_app"), transitions, clock);
    sender =
        new CustomerSmsSender(
            db.dataSource("fs_app"),
            (institution, account) ->
                Optional.of(new ContactDirectory.Contact("+250788000001", "en", "***4821")),
            institution ->
                Optional.of(
                    new InstitutionMessaging.Settings(
                        "FSBANK", "+250788100100", "https://verify.fsbank.rw/v/")),
            verifications,
            (senderId, phone, text) -> {
              sent.add(text);
              return "ATXid_" + sent.size();
            },
            new SmsCatalogue(),
            clock);
  }

  private String token() {
    String text = sent.getLast();
    int start =
        text.indexOf("https://verify.fsbank.rw/v/") + "https://verify.fsbank.rw/v/".length();
    return text.substring(start, start + 22);
  }

  private String one(String sql) throws Exception {
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      return r.next() ? r.getString(1) : null;
    }
  }

  @Test
  void theSmsCarriesSingleUseHttpsLinksAndIsRecordedAsSent() throws Exception {
    assertThat(sender.send(INSTITUTION, intent)).isEqualTo(CustomerSmsSender.Outcome.SENT);
    String text = sent.getLast();
    assertThat(Gsm7.fitsOneSegment(text)).isTrue();
    assertThat(text)
        .contains(
            "15000 RWF",
            "***4821",
            "https://verify.fsbank.rw/v/",
            "+250788100100",
            intent.get("parameters").get("reference_code").asString());
    assertThat(
            one(
                "SELECT provider_reference FROM fraudshield.customer_notifications"
                    + " WHERE event = 'SENT'"))
        .isEqualTo("ATXid_1");
    assertThat(one("SELECT expires_at - created_at FROM fraudshield.customer_verifications"))
        .isEqualTo("00:10:00");
    assertThat(
            verifications.issue(
                INSTITUTION, UUID.fromString(intent.get("auto_block_event_id").asString())))
        .as("one link per block")
        .isEmpty();
  }

  @Test
  void yesThisWasMeLiftsTheBlockOnceAndLabelsItLegitimate() throws Exception {
    sender.send(INSTITUTION, intent);
    String token = token();
    VerificationService.Lookup lookup = verifications.open(token);
    assertThat(lookup.view())
        .get()
        .satisfies(
            v -> {
              assertThat(v.maskedAccount()).startsWith("***");
              assertThat(v.localTime()).isEqualTo("12:00 CAT");
            });
    clock.advance(Duration.ofMinutes(9));
    assertThat(verifications.answer(token, true)).isEmpty();
    assertThat(ports.latest.get(blockedTransaction).decision()).isEqualTo(DecisionValue.APPROVE);
    assertThat(ports.latest.get(blockedTransaction).decidedBy())
        .isEqualTo(DecidedBy.CUSTOMER_VERIFICATION);
    assertThat(ports.eventsOf(DecisionEvent.LabelRecorded.class))
        .singleElement()
        .satisfies(l -> assertThat(l.fraud()).isFalse());
    assertThat(one("SELECT cause FROM fraudshield.unblock_events"))
        .isEqualTo("CUSTOMER_VERIFICATION");
    assertThat(verifications.answer(token, true)).contains(VerificationService.Unusable.USED);
    assertThat(verifications.open(token).unusable()).contains(VerificationService.Unusable.USED);
  }

  @Test
  void theLinkExpiresAtExactlyTenMinutesAndUnknownTokensRevealNothing() throws Exception {
    sender.send(INSTITUTION, intent);
    String token = token();
    clock.advance(Duration.ofMinutes(10).minusMillis(1));
    assertThat(verifications.open(token).view()).isPresent();
    clock.advance(Duration.ofMillis(1));
    assertThat(verifications.open(token).unusable()).contains(VerificationService.Unusable.EXPIRED);
    assertThat(verifications.answer(token, true)).contains(VerificationService.Unusable.EXPIRED);
    assertThat(verifications.open("AAAAAAAAAAAAAAAAAAAAAA").unusable())
        .contains(VerificationService.Unusable.UNKNOWN);
    assertThat(verifications.open("'; DROP TABLE x; --").unusable())
        .contains(VerificationService.Unusable.UNKNOWN);
    assertThat(ports.latest.get(blockedTransaction).decision()).isEqualTo(DecisionValue.DECLINE);
  }

  @Test
  void noThisWasNotMeKeepsTheBlockAndLabelsFraud() throws Exception {
    sender.send(INSTITUTION, intent);
    assertThat(verifications.answer(token(), false)).isEmpty();
    assertThat(ports.latest.get(blockedTransaction).decision()).isEqualTo(DecisionValue.DECLINE);
    assertThat(ports.eventsOf(DecisionEvent.LabelRecorded.class).getFirst().fraud()).isTrue();
    assertThat(one("SELECT count(*) FROM fraudshield.unblock_events")).isEqualTo("0");
  }

  @Test
  @Tag("D-25")
  void withoutSelfServiceTheSmsHasNoLinkAndSaysWhoToCall() throws Exception {
    ((tools.jackson.databind.node.ObjectNode) intent).put("verification_link_allowed", false);
    assertThat(sender.send(INSTITUTION, intent)).isEqualTo(CustomerSmsSender.Outcome.SENT);
    assertThat(sent.getLast()).doesNotContain("https://").contains("+250788100100");
    assertThat(one("SELECT count(*) FROM fraudshield.customer_verifications")).isEqualTo("0");
  }

  @Test
  void customersTheVaultDoesNotKnowAreRecordedAsFailed() throws Exception {
    CustomerSmsSender unknown =
        new CustomerSmsSender(
            db.dataSource("fs_app"),
            (i, a) -> Optional.empty(),
            i -> Optional.empty(),
            verifications,
            (s, p, t) -> "never",
            new SmsCatalogue(),
            clock);
    assertThat(unknown.send(INSTITUTION, intent)).isEqualTo(CustomerSmsSender.Outcome.FAILED);
    assertThat(
            one(
                "SELECT event FROM fraudshield.customer_notifications WHERE event <>"
                    + " 'REQUESTED'"))
        .isEqualTo("FAILED");
  }

  @Test
  @Tag("D-51")
  void theAfricasTalkingAdapterSpeaksTheProviderApi() throws Exception {
    List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    List<String> keys = new CopyOnWriteArrayList<>();
    HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext(
        "/version1/messaging",
        exchange -> {
          keys.add(exchange.getRequestHeaders().getFirst("apiKey"));
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          Map<String, String> form =
              java.util.Arrays.stream(body.split("&"))
                  .map(p -> p.split("=", 2))
                  .collect(
                      Collectors.toMap(
                          p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
          forms.add(form);
          boolean ok = !form.get("to").endsWith("9");
          byte[] reply =
              ("{\"SMSMessageData\":{\"Message\":\"Sent to 1/1\",\"Recipients\":[{"
                      + "\"statusCode\":"
                      + (ok ? 101 : 403)
                      + ",\"number\":\""
                      + form.get("to")
                      + "\",\"status\":\""
                      + (ok ? "Success" : "InvalidPhoneNumber")
                      + "\",\"messageId\":\"ATXid_42\"}]}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    provider.start();
    try {
      AfricasTalkingGateway gateway =
          new AfricasTalkingGateway(
              "http://127.0.0.1:" + provider.getAddress().getPort() + "/", "sandbox", "test-key");
      assertThat(gateway.send("FSBANK", "+250788000001", "Blocked 1 RWF")).isEqualTo("ATXid_42");
      assertThat(forms.getFirst())
          .containsEntry("username", "sandbox")
          .containsEntry("to", "+250788000001")
          .containsEntry("from", "FSBANK")
          .containsEntry("message", "Blocked 1 RWF");
      assertThat(keys).containsExactly("test-key");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> gateway.send("FSBANK", "+250788000009", "x"))
          .isInstanceOf(IOException.class);
    } finally {
      provider.stop(0);
    }
  }
}
