package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.UUID;

/** The real-time channel's routing, for the integration tests outside this package. */
public final class RealtimeProbe {

  private RealtimeProbe() {}

  /**
   * Whether the fan-out would deliver this {@code fs.alerts.changes} record to a session of the
   * given user: its institution, then point 4's filter, exactly as {@link FanOut} applies them.
   *
   * @param envelope the record, as the change publisher sent it
   * @param userId the session's user
   * @param institutionId the session's institution
   * @param role the session's role
   * @return true when the session would receive it
   */
  public static boolean wouldPush(
      byte[] envelope, UUID userId, UUID institutionId, StaffRole role) {
    AlertFrames.Routed routed =
        new AlertFrames()
            .route(ChangePublisher.TOPIC, envelope, 0)
            .orElseThrow(() -> new AssertionError("the change publisher sent an invalid record"));
    Binding binding =
        new Binding(
            new StaffClaims(userId, institutionId, role, null, null, 0, UUID.randomUUID()),
            Instant.now().plusSeconds(60));
    return routed.institutionId().equals(institutionId) && routed.visibility().admits(binding);
  }

  /**
   * The alert id of a change record.
   *
   * @param envelope the record
   * @return its entry
   */
  @SuppressWarnings("unchecked")
  public static String alertId(byte[] envelope) {
    AlertFrames.Routed routed =
        new AlertFrames().route(ChangePublisher.TOPIC, envelope, 0).orElseThrow();
    return (String) ((java.util.Map<String, Object>) routed.body().get("alert")).get("alert_id");
  }

  /**
   * A raised alert record of an institution.
   *
   * @param institutionId the institution
   * @return the record
   */
  public static byte[] raised(UUID institutionId) {
    return RealtimeFixtures.raised(institutionId, true);
  }
}
