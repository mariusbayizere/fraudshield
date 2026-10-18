package io.github.mariusbayizere.fraudshield.ingest.web;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/health}: {@code {"status":"UP"}} when this instance can accept and decide
 * requests (the rule fallback counts), with no component detail, which would tell attackers when
 * the model is down (ADR 0014).
 */
@RestController
public final class PublicHealthController {

  /**
   * The public status.
   *
   * @return UP
   */
  @GetMapping("/api/v1/health")
  public ResponseEntity<byte[]> health() {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8));
  }
}
