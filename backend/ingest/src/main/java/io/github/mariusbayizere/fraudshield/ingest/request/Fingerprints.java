package io.github.mariusbayizere.fraudshield.ingest.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The canonical request fingerprint (FR-01-03, ADR 0011 section 10): SHA-256 over the validated
 * fields in schema order, amounts as normalised decimal strings and the timestamp as a normalised
 * instant, so formatting differences (trailing zeros, fractional seconds) do not make one payment
 * look like two, while any change of value does.
 */
public final class Fingerprints {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Fingerprints() {}

  /**
   * The fingerprint.
   *
   * @param r a validated request
   * @return 32 bytes
   */
  public static byte[] of(IngestRequest r) {
    ArrayNode fields = JSON.createArrayNode();
    fields.add(r.transactionId().toString());
    fields.add(r.accountToken());
    fields.add(r.counterpartyToken());
    fields.add(r.amount().amount().stripTrailingZeros().toPlainString());
    fields.add(r.amount().currency().name());
    fields.add(r.channel().name());
    fields.add(r.merchantCategoryCode());
    fields.add(r.merchantName());
    fields.add(r.latitude());
    fields.add(r.longitude());
    fields.add(r.deviceToken());
    fields.add(r.agentToken());
    fields.add(r.counterpartyCountry());
    fields.add(r.transactionTimestamp().toString());
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(JSON.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }
}
