package io.github.mariusbayizere.fraudshield.ingest.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API documentation (FR-01-07): the OpenAPI 3.1 document at {@code /api/docs}.
 *
 * <p>It serves {@code contracts/openapi/fraudshield-api.yaml} byte for byte — the frozen contract
 * itself, packaged into the jar by the build — rather than a document generated from the code or
 * maintained by hand. Either of those could describe something the API does not do, which for a
 * contract that integrators build against is worse than no document at all. {@code ApiDocsTest}
 * fails if the served bytes differ from the file.
 *
 * <p>The document describes the public contract and carries no secrets, so it needs no API key; the
 * key filter guards {@code /api/v1/...} only. Its ETag is the digest of the bytes, so a client that
 * has it re-downloads only when the contract changes.
 */
@RestController
public final class ApiDocsController {

  /** Where the build puts the contract inside the jar. */
  static final String RESOURCE = "/openapi/fraudshield-api.yaml";

  private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

  private final byte[] document;
  private final String etag;

  /** Reads the contract once, at start-up: a missing one is a broken build, not a 404. */
  public ApiDocsController() {
    try (InputStream resource = ApiDocsController.class.getResourceAsStream(RESOURCE)) {
      if (resource == null) {
        throw new IllegalStateException(
            "the OpenAPI contract is not on the classpath at " + RESOURCE);
      }
      document = resource.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the OpenAPI contract", e);
    }
    try {
      etag =
          "\""
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document))
              + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }

  /**
   * {@code GET /api/docs}: the OpenAPI 3.1 document.
   *
   * @return the contract, as YAML
   */
  @GetMapping(path = {"/api/docs", "/api/docs/openapi.yaml"})
  public ResponseEntity<byte[]> document() {
    return ResponseEntity.ok()
        .contentType(YAML)
        .eTag(etag)
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
        .header("Content-Disposition", "inline; filename=\"fraudshield-api.yaml\"")
        .body(document);
  }

  /**
   * The document's digest, for tests and for operators comparing a deployment with a release.
   *
   * @return the ETag, quoted
   */
  public String etag() {
    return etag;
  }

  /**
   * The document as text.
   *
   * @return the contract
   */
  public String contract() {
    return new String(document, StandardCharsets.UTF_8);
  }
}
