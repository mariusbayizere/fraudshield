package io.github.mariusbayizere.fraudshield.ingest.web;

import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.deployment.SyntheticDataGuard;
import io.github.mariusbayizere.fraudshield.deployment.SyntheticDataStatus;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/environment}: exactly {@code {"synthetic_data": boolean}}, public, so the UI
 * can show the synthetic-data banner before login (D-21, ADR 0019). The flag is the one {@link
 * SyntheticDataGuard} decided at startup; the endpoint reveals nothing else about the deployment.
 */
@RestController
// Not final: @PreAuthorize makes Spring proxy the controller with a CGLIB subclass.
public class EnvironmentController {

  private final byte[] body;

  /**
   * Creates the controller.
   *
   * @param status the flag decided at startup
   */
  public EnvironmentController(
      @Qualifier(SyntheticDataGuard.STATUS_BEAN) SyntheticDataStatus status) {
    this.body =
        ("{\"synthetic_data\":" + Objects.requireNonNull(status, "status").syntheticData() + "}")
            .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The deployment's environment.
   *
   * @return the contract's {@code DeploymentEnvironment}
   */
  @ContractOperation("getDeploymentEnvironment")
  @PreAuthorize("permitAll()")
  @GetMapping(value = "/api/v1/environment", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> environment() {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.clone());
  }
}
