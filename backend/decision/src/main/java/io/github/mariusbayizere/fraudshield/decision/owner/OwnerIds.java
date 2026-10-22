package io.github.mariusbayizere.fraudshield.decision.owner;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Name-based ids (decision-owner contract §4.5). V81's {@code fs_name_uuid} computes the same ids
 * in SQL, which a test pins.
 */
public final class OwnerIds {

  private OwnerIds() {}

  /**
   * The version-3 UUID of a UTF-8 name, as {@link UUID#nameUUIDFromBytes(byte[])}.
   *
   * @param name the name
   * @return the id
   */
  public static UUID name(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The event id of a request's APPLIED or LABEL_ONLY row.
   *
   * @param requestId the request
   * @return the event id
   */
  public static UUID event(UUID requestId) {
    return name("event|" + requestId);
  }

  /**
   * The label id of a request's label.
   *
   * @param request the request
   * @return the label id
   */
  static UUID label(OwnerRequest request) {
    return switch (request) {
      case OwnerRequest.Effect e -> name("alert_label|" + e.decisionId());
      case OwnerRequest.SeniorOverride o -> name("alert_label|" + o.decisionId());
      case OwnerRequest.Customer c -> name("verification_label|" + c.verificationId());
      case OwnerRequest.Resolve r -> name("resolve_label|" + r.resolutionId());
      default -> throw new IllegalArgumentException(request.kind() + " carries no label");
    };
  }

  /**
   * The label id an analyst decision's or an override's label has, for an override's {@code
   * supersedes_label_id}.
   *
   * @param decisionId the decision
   * @return the label id
   */
  public static UUID decisionLabel(UUID decisionId) {
    return name("alert_label|" + decisionId);
  }

  /**
   * The idempotency key of the AUTO_* decision row a TIMEOUT writes (§5).
   *
   * @param requestId the TIMEOUT's request id
   * @return the key
   */
  static UUID autoDecisionKey(UUID requestId) {
    return name("auto_decision|" + requestId);
  }
}
