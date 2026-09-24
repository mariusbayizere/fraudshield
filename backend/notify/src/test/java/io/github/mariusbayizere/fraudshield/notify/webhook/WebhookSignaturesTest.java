package io.github.mariusbayizere.fraudshield.notify.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The shared vectors in contracts/webhooks, also verified by the Python reference (ADR 0012). */
@Tag("D-14")
class WebhookSignaturesTest {

  private static final Path WEBHOOKS = Path.of("..", "..", "contracts", "webhooks");

  static Stream<Arguments> signatureVectors() throws Exception {
    JsonNode root =
        new ObjectMapper()
            .readTree(Files.readString(WEBHOOKS.resolve("signature-test-vectors.json")));
    List<Arguments> cases = new ArrayList<>();
    for (JsonNode vector : root.get("vectors")) {
      cases.add(Arguments.of(vector.get("name").asString(), vector));
    }
    assertThat(cases).hasSize(18);
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signatureVectors")
  void everySignatureVectorGetsItsExpectedVerdict(String name, JsonNode vector) {
    List<String> secrets = new ArrayList<>();
    vector.get("secrets").forEach(s -> secrets.add(s.asString()));
    byte[] body = vector.get("body").asString().getBytes(StandardCharsets.UTF_8);
    assertThat(
            WebhookSignatures.verify(
                    vector.get("header").asString(),
                    body,
                    secrets,
                    vector.get("verify_at").asLong())
                .name())
        .as(name)
        .isEqualTo(vector.get("expected").asString());
  }

  @Test
  void whatWeSignVerifiesAndMatchesTheValidVector() throws Exception {
    JsonNode valid =
        new ObjectMapper()
            .readTree(Files.readString(WEBHOOKS.resolve("signature-test-vectors.json")))
            .get("vectors")
            .get(0);
    byte[] body = valid.get("body").asString().getBytes(StandardCharsets.UTF_8);
    String secret = valid.get("secrets").get(0).asString();
    String header = valid.get("header").asString();
    long t = Long.parseLong(header.substring(2, header.indexOf(',')));
    assertThat(WebhookSignatures.header(List.of(secret), t, body)).isEqualTo(header);
    String rotating =
        WebhookSignatures.header(
            List.of(new StringBuilder(secret).reverse().toString(), secret), t, body);
    assertThat(rotating).startsWith("t=" + t + ",v1=").contains(",v1=");
    assertThat(WebhookSignatures.verify(rotating, body, List.of(secret), t))
        .isEqualTo(WebhookSignatures.Verdict.VALID);
    assertThatThrownBy(() -> WebhookSignatures.header(List.of(), t, body))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void receiversOrderBySequenceNotByDecision() throws Exception {
    JsonNode root =
        new ObjectMapper()
            .readTree(Files.readString(WEBHOOKS.resolve("delivery-ordering-vectors.json")));
    int checked = 0;
    for (JsonNode scenario : root.get("scenarios")) {
      Integer last = null;
      for (JsonNode delivery : scenario.get("deliveries")) {
        int sequence = delivery.get("decision_sequence").asInt();
        boolean apply = WebhookSignatures.shouldApply(last, sequence);
        assertThat(apply ? "APPLY" : "IGNORE")
            .as(scenario.get("name").asString())
            .isEqualTo(delivery.get("expected").asString());
        if (apply) {
          last = sequence;
        }
        checked++;
      }
    }
    assertThat(checked).isPositive();
  }
}
