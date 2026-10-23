package io.github.mariusbayizere.fraudshield.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.deployment.SyntheticDataStatus;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The banner's flag, exactly as the contract's {@code DeploymentEnvironment} (ADR 0019). */
class EnvironmentControllerTest {

  private static String body(boolean synthetic) {
    ResponseEntity<byte[]> response =
        new EnvironmentController(new SyntheticDataStatus(synthetic)).environment();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    return new String(response.getBody(), StandardCharsets.UTF_8);
  }

  @Test
  @Tag("D-21")
  void reportsTheStartupFlagAndNothingElse() {
    assertThat(body(true)).isEqualTo("{\"synthetic_data\":true}");
    assertThat(body(false)).isEqualTo("{\"synthetic_data\":false}");
  }
}
