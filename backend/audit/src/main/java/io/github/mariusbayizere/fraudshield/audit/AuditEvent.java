package io.github.mariusbayizere.fraudshield.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit record (FR-06-06, D-32). The database assigns the chain position and hashes.
 *
 * <p>{@code before} and {@code after} hold the changed values of the entity. They must never hold
 * credential material (password hashes, token hashes, API-key secrets) or customer PII; callers
 * build them from explicit field lists, never by serialising whole entities.
 *
 * @param institutionId institution the event belongs to
 * @param type one of the twelve event types
 * @param action what happened within the type, upper snake case
 * @param entityType kind of entity affected, for example {@code user}
 * @param entityId identifier of the entity
 * @param actor staff member who acted, or null for system events and unauthenticated requests
 * @param before values before the change, or null
 * @param after values after the change, or null
 * @param ipAddress client address, or null
 * @param userAgent client user agent, or null
 * @param correlationId request correlation ID, or null
 * @param eventAt business time of the event
 */
public record AuditEvent(
    UUID institutionId,
    AuditEventType type,
    String action,
    String entityType,
    String entityId,
    AuditActor actor,
    Map<String, Object> before,
    Map<String, Object> after,
    String ipAddress,
    String userAgent,
    UUID correlationId,
    Instant eventAt) {

  private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z_]{1,63}$");
  private static final int MAX_ENTITY_TYPE = 64;
  private static final int MAX_ENTITY_ID = 128;
  private static final int MAX_USER_AGENT = 1024;

  /** Validates the fields against the audit_events constraints before any database round trip. */
  public AuditEvent {
    Objects.requireNonNull(institutionId, "institutionId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(eventAt, "eventAt");
    if (!ACTION.matcher(action).matches()) {
      throw new IllegalArgumentException("audit action must be upper snake case: " + action);
    }
    if (entityType.isEmpty() || entityType.length() > MAX_ENTITY_TYPE) {
      throw new IllegalArgumentException("entity type must be 1-64 characters");
    }
    if (entityId.isEmpty() || entityId.length() > MAX_ENTITY_ID) {
      throw new IllegalArgumentException("entity ID must be 1-128 characters");
    }
    if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
      userAgent = userAgent.substring(0, MAX_USER_AGENT);
    }
    before = copyAllowingNullValues(before);
    after = copyAllowingNullValues(after);
  }

  // Map.copyOf rejects null values, but "phone was null" is a meaningful before value.
  private static Map<String, Object> copyAllowingNullValues(Map<String, Object> values) {
    return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /**
   * Values before the change.
   *
   * @return an unmodifiable view, or null
   */
  @Override
  public Map<String, Object> before() {
    return before == null ? null : Collections.unmodifiableMap(before);
  }

  /**
   * Values after the change.
   *
   * @return an unmodifiable view, or null
   */
  @Override
  public Map<String, Object> after() {
    return after == null ? null : Collections.unmodifiableMap(after);
  }

  /**
   * The actor, if a staff member acted.
   *
   * @return the actor
   */
  public Optional<AuditActor> actorIfAny() {
    return Optional.ofNullable(actor);
  }

  /**
   * Starts an event for an institution.
   *
   * @param institutionId institution
   * @param type event type
   * @param action action within the type
   * @return a builder
   */
  public static Builder of(UUID institutionId, AuditEventType type, String action) {
    return new Builder(institutionId, type, action);
  }

  /** Builder for the optional fields. */
  public static final class Builder {
    private final UUID institutionId;
    private final AuditEventType type;
    private final String action;
    private String entityType;
    private String entityId;
    private AuditActor actor;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private String ipAddress;
    private String userAgent;
    private UUID correlationId;

    private Builder(UUID institutionId, AuditEventType type, String action) {
      this.institutionId = institutionId;
      this.type = type;
      this.action = action;
    }

    /**
     * Sets the affected entity.
     *
     * @param type entity kind
     * @param id entity identifier
     * @return this builder
     */
    public Builder entity(String type, Object id) {
      this.entityType = type;
      this.entityId = String.valueOf(id);
      return this;
    }

    /**
     * Sets the acting staff member.
     *
     * @param value actor, or null
     * @return this builder
     */
    public Builder actor(AuditActor value) {
      this.actor = value;
      return this;
    }

    /**
     * Sets the values before the change.
     *
     * @param value values, or null
     * @return this builder
     */
    public Builder before(Map<String, Object> value) {
      this.before = copyAllowingNullValues(value);
      return this;
    }

    /**
     * Sets the values after the change.
     *
     * @param value values, or null
     * @return this builder
     */
    public Builder after(Map<String, Object> value) {
      this.after = copyAllowingNullValues(value);
      return this;
    }

    /**
     * Sets the request context.
     *
     * @param context client address, user agent and correlation ID
     * @return this builder
     */
    public Builder context(RequestContext context) {
      if (context != null) {
        this.ipAddress = context.ipAddress();
        this.userAgent = context.userAgent();
        this.correlationId = context.correlationId();
      }
      return this;
    }

    /**
     * Builds the event.
     *
     * @param eventAt business time
     * @return the event
     */
    public AuditEvent at(Instant eventAt) {
      return new AuditEvent(
          institutionId,
          type,
          action,
          entityType,
          entityId,
          actor,
          before,
          after,
          ipAddress,
          userAgent,
          correlationId,
          eventAt);
    }
  }
}
