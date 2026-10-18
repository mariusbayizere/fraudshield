package io.github.mariusbayizere.fraudshield.admin.apikeys;

import io.github.mariusbayizere.fraudshield.admin.support.AdminContext;
import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyRecord;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyService;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * API keys for core-banking integrations (FR-06-07, D-19): name, scopes and last four characters
 * are listed; the raw key and webhook secret are returned exactly once, with {@code Cache-Control:
 * no-store}.
 */
@RestController
@Validated
@RequestMapping("/api/v1/admin/api-keys")
public class ApiKeyAdminController {

  private static final String ADMIN = "hasAnyRole('ADMIN')";

  private final ApiKeyService keys;
  private final TenantTransactions tenants;
  private final AdminContext admins;

  /**
   * Creates the controller.
   *
   * @param keys API-key service
   * @param tenants tenant transactions
   * @param admins acting-administrator resolver
   */
  public ApiKeyAdminController(
      ApiKeyService keys, TenantTransactions tenants, AdminContext admins) {
    this.keys = Objects.requireNonNull(keys);
    this.tenants = Objects.requireNonNull(tenants);
    this.admins = Objects.requireNonNull(admins);
  }

  /**
   * ApiKeyCreate.
   *
   * @param name name
   * @param scopes scopes, unique, at least one
   * @param webhookUrl webhook URL or null
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Create(
      @NotNull @Pattern(regexp = "^[A-Za-z0-9 _.-]{3,80}$") String name,
      @NotNull @Size(min = 1)
          List<@NotNull @Pattern(regexp = "^(ingest:write|decisions:read|jobs:read)$") String>
              scopes,
      @Pattern(regexp = "^https://.*") @Size(max = 2048) String webhookUrl) {

    /** Copies the scopes. */
    public Create {
      scopes = scopes == null ? null : List.copyOf(scopes);
    }
  }

  /**
   * Keys of the caller's institution.
   *
   * @param jwt caller
   * @return the keys
   */
  @ContractOperation("listApiKeys")
  @PreAuthorize(ADMIN)
  @GetMapping
  public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
    return keys.list(AccessTokens.claims(jwt).institutionId()).stream()
        .map(ApiKeyAdminController::view)
        .toList();
  }

  /**
   * Creates a key.
   *
   * @param jwt caller
   * @param body key
   * @param request request
   * @return 201 with the raw key, not cacheable
   */
  @ContractOperation("createApiKey")
  @PreAuthorize(ADMIN)
  @PostMapping
  public ResponseEntity<Map<String, Object>> create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody Create body,
      HttpServletRequest request) {
    if (new HashSet<>(body.scopes()).size() != body.scopes().size()) {
      throw ProblemException.validation("scopes", "duplicate_items", "Scopes must be unique");
    }
    StaffClaims claims = AccessTokens.claims(jwt);
    List<ApiKeyScope> scopes =
        body.scopes().stream().map(s -> ApiKeyScope.parse(s).orElseThrow()).toList();
    ApiKeyService.Issued issued =
        keys.create(
            claims.institutionId(),
            body.name(),
            scopes,
            body.webhookUrl(),
            actor(claims),
            Requests.context(request));
    return created(issued);
  }

  /**
   * Rotates a key with a 24-hour overlap.
   *
   * @param jwt caller
   * @param keyId key
   * @param request request
   * @return 201 with the replacement's raw key
   */
  @ContractOperation("rotateApiKey")
  @PreAuthorize(ADMIN)
  @PostMapping("/{key_id}/rotate")
  public ResponseEntity<Map<String, Object>> rotate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("key_id") @Pattern(regexp = "^[a-z0-9]{12}$") String keyId,
      HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return created(
        keys.rotate(claims.institutionId(), keyId, actor(claims), Requests.context(request)));
  }

  /**
   * Revokes a key.
   *
   * @param jwt caller
   * @param keyId key
   * @param request request
   * @return 204
   */
  @ContractOperation("revokeApiKey")
  @PreAuthorize(ADMIN)
  @PostMapping("/{key_id}/revoke")
  public ResponseEntity<Void> revoke(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("key_id") @Pattern(regexp = "^[a-z0-9]{12}$") String keyId,
      HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    keys.revoke(claims.institutionId(), keyId, actor(claims), Requests.context(request));
    return ResponseEntity.noContent().build();
  }

  private AuditActor actor(StaffClaims claims) {
    return tenants.inTenant(claims.institutionId(), () -> admins.actor(claims));
  }

  private static ResponseEntity<Map<String, Object>> created(ApiKeyService.Issued issued) {
    Map<String, Object> body = view(issued.record());
    body.put("raw_key", issued.rawKey());
    body.put("webhook_signing_secret", issued.webhookSigningSecret());
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(body);
  }

  private static Map<String, Object> view(ApiKeyRecord key) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("key_id", key.keyId());
    view.put("name", key.name());
    view.put("scopes", key.scopes().stream().map(ApiKeyScope::value).toList());
    view.put("last_four", key.lastFour());
    view.put("state", key.state());
    view.put("created_at", key.createdAt().toString());
    view.put("expires_at", key.expiresAt() == null ? null : key.expiresAt().toString());
    view.put("last_used_at", key.lastUsedAt() == null ? null : key.lastUsedAt().toString());
    return view;
  }
}
