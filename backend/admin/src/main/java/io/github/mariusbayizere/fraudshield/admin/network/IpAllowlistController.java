package io.github.mariusbayizere.fraudshield.admin.network;

import io.github.mariusbayizere.fraudshield.admin.support.AdminContext;
import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.OfficeIpAllowlist;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Office egress ranges with a higher sign-in ceiling (D-26). */
@RestController
@RequestMapping("/api/v1/admin/ip-allowlist")
public class IpAllowlistController {

  private static final String ADMIN = "hasAnyRole('ADMIN')";

  private final TenantTransactions tenants;
  private final OfficeIpAllowlist allowlist;
  private final AdminContext admins;
  private final AuditLog audit;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param tenants tenant transactions
   * @param allowlist allowlist
   * @param admins acting-administrator resolver
   * @param audit audit log
   * @param clock clock
   */
  public IpAllowlistController(
      TenantTransactions tenants,
      OfficeIpAllowlist allowlist,
      AdminContext admins,
      AuditLog audit,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants);
    this.allowlist = Objects.requireNonNull(allowlist);
    this.admins = Objects.requireNonNull(admins);
    this.audit = Objects.requireNonNull(audit);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * IpAllowlistEntryCreate.
   *
   * @param cidr network
   * @param description description
   */
  public record Create(
      @NotNull @Size(max = 43) String cidr,
      @NotNull @Size(min = 3, max = 200) String description) {}

  /**
   * Entries of the caller's institution.
   *
   * @param jwt caller
   * @return the entries
   */
  @ContractOperation("listOfficeIpAllowlist")
  @PreAuthorize(ADMIN)
  @GetMapping
  public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants.inTenant(
        claims.institutionId(), () -> allowlist.list().stream().map(this::view).toList());
  }

  /**
   * Adds a range.
   *
   * @param jwt caller
   * @param body range
   * @param request request
   * @return 201 with the entry
   */
  @ContractOperation("addOfficeIpRange")
  @PreAuthorize(ADMIN)
  @PostMapping
  public ResponseEntity<Map<String, Object>> add(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody Create body,
      HttpServletRequest request) {
    String cidr =
        Cidr.canonical(body.cidr())
            .orElseThrow(
                () ->
                    ProblemException.validation(
                        "cidr",
                        "invalid_cidr",
                        "Not a network address with a valid prefix and no host bits"));
    StaffClaims claims = AccessTokens.claims(jwt);
    Map<String, Object> entry =
        tenants.inTenant(
            claims.institutionId(),
            () -> {
              AuditActor actor = admins.actor(claims);
              if (allowlist.exists(cidr)) {
                throw ProblemException.of(
                    "conflict", 409, "Conflict", "This range is already listed");
              }
              Instant now = clock.instant();
              OfficeIpAllowlist.Entry added =
                  allowlist.add(
                      claims.institutionId(), cidr, body.description(), actor.userId(), now);
              audit.record(
                  AuditEvent.of(
                          claims.institutionId(),
                          AuditEventType.USER_ADMIN,
                          "IP_ALLOWLIST_ENTRY_ADDED")
                      .entity("office_ip_allowlist", added.id())
                      .actor(actor)
                      .after(Map.of("cidr", added.cidr(), "description", added.description()))
                      .context(Requests.context(request))
                      .at(now));
              return view(added);
            });
    return ResponseEntity.status(HttpStatus.CREATED).body(entry);
  }

  /**
   * Removes a range.
   *
   * @param jwt caller
   * @param entryId entry
   * @param request request
   * @return 204
   */
  @ContractOperation("removeOfficeIpRange")
  @PreAuthorize(ADMIN)
  @DeleteMapping("/{entry_id}")
  public ResponseEntity<Void> remove(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("entry_id") UUID entryId,
      HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    tenants.runInTenant(
        claims.institutionId(),
        () -> {
          AuditActor actor = admins.actor(claims);
          OfficeIpAllowlist.Entry entry =
              allowlist
                  .find(entryId)
                  .orElseThrow(() -> ProblemException.notFound("The allowlist entry"));
          allowlist.remove(claims.institutionId(), entryId);
          audit.record(
              AuditEvent.of(
                      claims.institutionId(),
                      AuditEventType.USER_ADMIN,
                      "IP_ALLOWLIST_ENTRY_REMOVED")
                  .entity("office_ip_allowlist", entryId)
                  .actor(actor)
                  .before(Map.of("cidr", entry.cidr(), "description", entry.description()))
                  .context(Requests.context(request))
                  .at(clock.instant()));
        });
    return ResponseEntity.noContent().build();
  }

  private Map<String, Object> view(OfficeIpAllowlist.Entry entry) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("entry_id", entry.id());
    view.put("cidr", entry.cidr());
    view.put("description", entry.description());
    Map<String, Object> creator = new LinkedHashMap<>();
    creator.put("user_id", entry.createdBy().userId());
    creator.put("first_name", entry.createdBy().firstName());
    creator.put("last_name", entry.createdBy().lastName());
    creator.put("role", entry.createdBy().role());
    view.put("created_by", creator);
    view.put("created_at", entry.createdAt().toString());
    return view;
  }
}
