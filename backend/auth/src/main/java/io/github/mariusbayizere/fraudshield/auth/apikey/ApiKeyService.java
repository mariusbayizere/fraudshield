package io.github.mariusbayizere.fraudshield.auth.apikey;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.crypto.SecretBox;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * API-key lifecycle for administrators (FR-06-07, D-19): create, rotate with a 24-hour overlap,
 * revoke, list. The raw key and the webhook signing secret are returned once and never stored; the
 * key's HMAC and the ciphertext webhook secret are.
 */
public final class ApiKeyService {

  private final TenantTransactions tenants;
  private final ApiKeyRepository repository;
  private final ApiKeyAuthenticator authenticator;
  private final WebhookUrlValidator webhooks;
  private final SecretBox secretBox;
  private final AuditLog audit;
  private final Clock clock;
  private final String environment;
  private final Map<Integer, byte[]> peppers;
  private final int currentPepperVersion;
  private final Duration rotationOverlap;

  /**
   * Creates the service.
   *
   * @param tenants tenant transactions
   * @param repository key repository
   * @param authenticator authenticator, told about every change
   * @param webhooks webhook URL validator
   * @param secretBox encryption of webhook secrets
   * @param audit audit log
   * @param clock clock
   * @param environment key environment segment
   * @param peppers peppers by version
   * @param currentPepperVersion pepper version for new keys
   * @param rotationOverlap how long a rotated key stays valid
   */
  public ApiKeyService(
      TenantTransactions tenants,
      ApiKeyRepository repository,
      ApiKeyAuthenticator authenticator,
      WebhookUrlValidator webhooks,
      SecretBox secretBox,
      AuditLog audit,
      Clock clock,
      String environment,
      Map<Integer, byte[]> peppers,
      int currentPepperVersion,
      Duration rotationOverlap) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
    this.secretBox = Objects.requireNonNull(secretBox, "secretBox");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.environment = ApiKeyFormat.requireEnvironment(environment);
    this.peppers = Map.copyOf(peppers);
    if (!this.peppers.containsKey(currentPepperVersion)) {
      throw new IllegalStateException(
          "no pepper for the current pepper version " + currentPepperVersion);
    }
    this.currentPepperVersion = currentPepperVersion;
    this.rotationOverlap = Objects.requireNonNull(rotationOverlap, "rotationOverlap");
  }

  /**
   * A newly issued key.
   *
   * @param record the stored key
   * @param rawKey the raw key, shown once
   * @param webhookSigningSecret the webhook secret, shown once, or null without a webhook
   */
  public record Issued(ApiKeyRecord record, String rawKey, String webhookSigningSecret) {

    @Override
    public String toString() {
      return "Issued[keyId=" + record.keyId() + ", secrets=<redacted>]";
    }
  }

  /**
   * Creates a key.
   *
   * @param institutionId institution
   * @param name name
   * @param scopes scopes, at least one
   * @param webhookUrl webhook URL, or null
   * @param actor administrator
   * @param context request context
   * @return the key with its secrets
   */
  public Issued create(
      UUID institutionId,
      String name,
      List<ApiKeyScope> scopes,
      String webhookUrl,
      AuditActor actor,
      RequestContext context) {
    if (webhookUrl != null && !webhooks.allowed(webhookUrl)) {
      throw ProblemException.validation(
          "webhook_url",
          "webhook_url_not_allowed",
          "The webhook URL must be HTTPS on a public host name");
    }
    return tenants.inTenant(
        institutionId,
        () -> {
          if (repository.liveNameTaken(name)) {
            throw ProblemException.of(
                "conflict", 409, "Conflict", "A live key already has this name");
          }
          Issued issued = issue(institutionId, name, scopes, webhookUrl, actor.userId());
          audit.record(
              AuditEvent.of(institutionId, AuditEventType.API_KEY_LIFECYCLE, "API_KEY_CREATED")
                  .entity("api_key", issued.record().keyId())
                  .actor(actor)
                  .after(describe(issued.record()))
                  .context(context)
                  .at(clock.instant()));
          return issued;
        });
  }

  /**
   * Rotates a key: issues a replacement with the same name, scopes and webhook, and keeps the old
   * key valid for the overlap (FR-06-07: 24 hours).
   *
   * @param institutionId institution
   * @param keyId key to rotate
   * @param actor administrator
   * @param context request context
   * @return the replacement with its secrets
   */
  public Issued rotate(UUID institutionId, String keyId, AuditActor actor, RequestContext context) {
    Issued issued =
        tenants.inTenant(
            institutionId,
            () -> {
              ApiKeyRecord old =
                  repository
                      .findForUpdate(keyId)
                      .orElseThrow(() -> ProblemException.notFound("The API key"));
              if (!"ACTIVE".equals(old.state())) {
                throw ProblemException.of(
                    "conflict", 409, "Conflict", "Only an ACTIVE key can be rotated");
              }
              Instant now = clock.instant();
              Issued replacement =
                  issue(institutionId, old.name(), old.scopes(), old.webhookUrl(), actor.userId());
              Instant overlapEnds = now.plus(rotationOverlap);
              repository.markRotating(old.id(), replacement.record().id(), overlapEnds);
              Map<String, Object> after = describe(replacement.record());
              after.put("replaces", old.keyId());
              after.put("old_key_expires_at", overlapEnds.toString());
              audit.record(
                  AuditEvent.of(institutionId, AuditEventType.API_KEY_LIFECYCLE, "API_KEY_ROTATED")
                      .entity("api_key", old.keyId())
                      .actor(actor)
                      .before(describe(old))
                      .after(after)
                      .context(context)
                      .at(now));
              return replacement;
            });
    AfterCommit.run(() -> authenticator.keyChanged(keyId));
    return issued;
  }

  /**
   * Revokes a key; requests with it fail within 5 seconds (FR-06-07). Revoking a revoked key does
   * nothing.
   *
   * @param institutionId institution
   * @param keyId key to revoke
   * @param actor administrator
   * @param context request context
   */
  public void revoke(UUID institutionId, String keyId, AuditActor actor, RequestContext context) {
    tenants.runInTenant(
        institutionId,
        () -> {
          ApiKeyRecord key =
              repository
                  .findForUpdate(keyId)
                  .orElseThrow(() -> ProblemException.notFound("The API key"));
          if ("REVOKED".equals(key.state())) {
            return;
          }
          Instant now = clock.instant();
          repository.revoke(key.id(), now);
          audit.record(
              AuditEvent.of(institutionId, AuditEventType.API_KEY_LIFECYCLE, "API_KEY_REVOKED")
                  .entity("api_key", keyId)
                  .actor(actor)
                  .before(describe(key))
                  .after(Map.of("state", "REVOKED"))
                  .context(context)
                  .at(now));
          AfterCommit.run(() -> authenticator.keyChanged(keyId));
        });
  }

  /**
   * Keys of an institution.
   *
   * @param institutionId institution
   * @return the keys, newest first
   */
  public List<ApiKeyRecord> list(UUID institutionId) {
    return tenants.inTenant(institutionId, repository::list);
  }

  /**
   * The webhook signing secret of a key, for the webhook dispatcher (M6).
   *
   * @param institutionId institution
   * @param apiKeyId key row ID
   * @param keyId public key ID (bound into the ciphertext)
   * @return the secret, or null without a webhook
   */
  public String webhookSecret(UUID institutionId, UUID apiKeyId, String keyId) {
    return tenants.inTenant(
        institutionId,
        () ->
            repository
                .webhookSecretCiphertext(apiKeyId)
                .map(ciphertext -> secretBox.open(ciphertext, webhookAad(keyId)))
                .orElse(null));
  }

  private Issued issue(
      UUID institutionId,
      String name,
      List<ApiKeyScope> scopes,
      String webhookUrl,
      UUID createdBy) {
    ApiKeyFormat.ParsedKey key = ApiKeyFormat.generate(environment);
    String webhookSecret = webhookUrl == null ? null : ApiKeyFormat.webhookSecret(environment);
    ApiKeyRecord record =
        repository.insert(
            new ApiKeyRepository.NewKey(
                institutionId,
                key.keyId(),
                name,
                Crypto.hmacSha256(
                    peppers.get(currentPepperVersion),
                    key.secret().getBytes(StandardCharsets.US_ASCII)),
                currentPepperVersion,
                key.lastFour(),
                scopes,
                webhookUrl,
                webhookSecret == null
                    ? null
                    : secretBox.seal(webhookSecret, webhookAad(key.keyId())),
                webhookSecret == null ? null : secretBox.keyId(),
                createdBy));
    return new Issued(record, key.raw(), webhookSecret);
  }

  private static String webhookAad(String keyId) {
    return "api-key-webhook-secret:" + keyId;
  }

  private static Map<String, Object> describe(ApiKeyRecord key) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("key_id", key.keyId());
    values.put("name", key.name());
    values.put("scopes", key.scopes().stream().map(ApiKeyScope::value).toList());
    values.put("state", key.state());
    values.put("webhook_url", key.webhookUrl());
    return values;
  }
}
