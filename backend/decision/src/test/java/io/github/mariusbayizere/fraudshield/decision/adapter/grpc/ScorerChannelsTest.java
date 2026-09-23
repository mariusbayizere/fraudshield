package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D-16: mTLS unless plaintext is chosen explicitly; keys never printed. */
@Tag("D-16")
class ScorerChannelsTest {

  @Test
  void plaintextMustBeExplicitAndTlsNeedsEveryPart() {
    assertThatThrownBy(() -> new ScorerChannels.Settings("scorer:9090", false, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    ManagedChannel plain =
        ScorerChannels.open(new ScorerChannels.Settings("localhost:1", true, null, null, null));
    assertThat(plain.authority()).isEqualTo("localhost:1");
    plain.shutdownNow();
  }

  @Test
  void invalidCertificatesFailFastAndSecretsAreRedacted() {
    byte[] junk = "not a certificate".getBytes(StandardCharsets.US_ASCII);
    ScorerChannels.Settings settings =
        new ScorerChannels.Settings("scorer:9090", false, junk, junk, junk);
    assertThat(settings.toString()).doesNotContain("not a certificate").contains("<redacted>");
    assertThat(settings)
        .isEqualTo(new ScorerChannels.Settings("scorer:9090", false, junk, junk, junk))
        .hasSameHashCodeAs(new ScorerChannels.Settings("scorer:9090", false, junk, junk, junk));
    assertThat(settings.clientKeyPem()).isEqualTo(junk).isNotSameAs(junk);
    assertThatThrownBy(() -> ScorerChannels.open(settings))
        .isInstanceOf(IllegalStateException.class);
  }
}
