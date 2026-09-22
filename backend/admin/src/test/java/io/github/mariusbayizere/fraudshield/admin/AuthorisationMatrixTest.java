package io.github.mariusbayizere.fraudshield.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyService;
import io.github.mariusbayizere.fraudshield.auth.security.Access;
import io.github.mariusbayizere.fraudshield.auth.security.ContractPolicy;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import io.github.mariusbayizere.fraudshield.auth.testing.Instances;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * M7 gate: the role x endpoint authorisation matrix (FR-07-01, FR-07-05, ADR 0014).
 *
 * <p>Two checks against the golden matrix:
 *
 * <ol>
 *   <li>Every controller method names its contract operation, has the contract's method and path,
 *       and carries a {@code @PreAuthorize} rule equal to the matrix (H.2: a test fails if any
 *       endpoint lacks an explicit rule).
 *   <li>Every application-port operation of the contract is called over HTTP by anonymous, by each
 *       of the four roles and by API keys of each scope: every caller the matrix does not list gets
 *       403 (401 when anonymous), and every caller it lists gets past authorisation. Operations of
 *       later milestones have no handler yet; they still refuse unlisted callers, which proves the
 *       deny-by-default policy protects them before they exist.
 * </ol>
 */
@Tag("FR-07-01")
@Tag("FR-07-05")
class AuthorisationMatrixTest extends AuthIntegrationTest {

  private static final Pattern ROLE_RULE =
      Pattern.compile("^hasAnyRole\\(((?:'[A-Z_]+'(?:, )?)+)\\)$");
  private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

  @Autowired private ContractPolicy policy;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Autowired private ApiKeyService apiKeys;

  @Test
  void everyHandlerDeclaresTheContractOperationAndItsRoles() {
    List<String> problems = new ArrayList<>();
    Set<String> implemented = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        mappings.getHandlerMethods().entrySet()) {
      Method method = entry.getValue().getMethod();
      if (method.getDeclaringClass().getName().startsWith("org.springframework")) {
        continue; // Spring Boot's own error controller
      }
      ContractOperation operation = method.getAnnotation(ContractOperation.class);
      PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
      String where = method.getDeclaringClass().getSimpleName() + "." + method.getName();
      if (operation == null || rule == null) {
        problems.add(where + " lacks @ContractOperation or @PreAuthorize");
        continue;
      }
      ContractPolicy.Operation contract = policy.operations().get(operation.value());
      if (contract == null) {
        problems.add(where + " names unknown operation " + operation.value());
        continue;
      }
      implemented.add(operation.value());
      String httpMethod =
          entry.getKey().getMethodsCondition().getMethods().iterator().next().name();
      Set<String> paths = entry.getKey().getPathPatternsCondition().getPatternValues();
      if (!httpMethod.equals(contract.method()) || !paths.contains(contract.path())) {
        problems.add(
            where
                + " is "
                + httpMethod
                + " "
                + paths
                + " but the contract says "
                + contract.method()
                + " "
                + contract.path());
      }
      String expected = expectedRule(contract.access());
      if (!normalise(rule.value()).equals(expected)) {
        problems.add(
            where + " has @PreAuthorize(\"" + rule.value() + "\") but the matrix says " + expected);
      }
    }
    assertThat(problems).isEmpty();
    assertThat(implemented)
        .as("M7 implements every auth and staff-administration operation")
        .contains(
            "login",
            "loginWithGoogle",
            "refreshToken",
            "logout",
            "logoutEverywhere",
            "getCurrentUser",
            "register",
            "checkAvailability",
            "verifyEmail",
            "requestPasswordReset",
            "verifyPasswordResetCode",
            "completePasswordReset",
            "changePassword",
            "unlockAccount",
            "getJwks",
            "listUsers",
            "createUser",
            "getUser",
            "updateUser",
            "listPendingApprovals",
            "decideApproval",
            "listOfficeIpAllowlist",
            "addOfficeIpRange",
            "removeOfficeIpRange",
            "listApiKeys",
            "createApiKey",
            "rotateApiKey",
            "revokeApiKey",
            "searchAuditEvents");
  }

  private static String expectedRule(Access access) {
    if (access.isPublic() || access.refreshCookie()) {
      return "permitAll()";
    }
    if (!access.roles().isEmpty()) {
      return "roles:"
          + access.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
    return "scopes:"
        + access.scopes().stream()
            .map(ApiKeyScope::value)
            .sorted()
            .collect(Collectors.joining(","));
  }

  private static String normalise(String rule) {
    Matcher matcher = ROLE_RULE.matcher(rule);
    if (!matcher.matches()) {
      return rule;
    }
    Matcher quoted = QUOTED.matcher(matcher.group(1));
    TreeSet<String> roles = new TreeSet<>();
    while (quoted.find()) {
      roles.add(quoted.group(1));
    }
    return "roles:" + String.join(",", roles);
  }

  private record Caller(String name, StaffRole role, Set<ApiKeyScope> scopes, String[] headers) {}

  @Test
  @Tag("FR-06-07")
  void everyCallerOutsideTheMatrixIsRefusedOnEveryOperation() {
    List<Caller> callers = new ArrayList<>();
    callers.add(new Caller("anonymous", null, Set.of(), csrf()));
    Map<StaffRole, Account> accounts = new LinkedHashMap<>();
    for (StaffRole role : StaffRole.values()) {
      Account account = createAccount(BANK_A, role.name(), "ACTIVE");
      accounts.put(role, account);
      callers.add(new Caller(role.name(), role, Set.of(), csrf(issueSession(account).bearer())));
    }
    AuditActor admin =
        new AuditActor(accounts.get(StaffRole.ADMIN).id(), "Amani", "Uwase", StaffRole.ADMIN);
    for (Set<ApiKeyScope> scopes :
        List.of(
            EnumSet.of(ApiKeyScope.INGEST_WRITE),
            EnumSet.of(ApiKeyScope.DECISIONS_READ),
            EnumSet.of(ApiKeyScope.JOBS_READ),
            EnumSet.allOf(ApiKeyScope.class))) {
      String raw =
          apiKeys
              .create(
                  BANK_A,
                  "Matrix " + UUID.randomUUID().toString().substring(0, 8),
                  List.copyOf(scopes),
                  null,
                  admin,
                  RequestContext.SYSTEM)
              .rawKey();
      callers.add(new Caller("api-key" + scopes, null, scopes, csrf("X-API-Key", raw)));
    }

    List<String> violations = new ArrayList<>();
    StringBuilder matrix = new StringBuilder("{\"calls\": [\n");
    int calls = 0;
    for (ContractPolicy.Operation operation : policy.operations().values()) {
      for (Caller caller : callers) {
        Http.Response response = call(operation, caller);
        calls++;
        boolean listed = listed(operation.access(), caller);
        int status = response.status();
        matrix.append(
            String.format(
                "  {\"operation\": \"%s\", \"caller\": \"%s\", \"listed\": %s, \"status\": %d},%n",
                operation.operationId(), caller.name(), listed, status));
        if (listed
            && (status == 401 || status == 403)
            && !legitimateRefusal(operation, caller, response)) {
          violations.add(
              operation.operationId()
                  + " "
                  + caller.name()
                  + " listed but got "
                  + status
                  + " "
                  + response.body());
        }
        if (!listed) {
          int expected = caller.role() == null && caller.scopes().isEmpty() ? 401 : 403;
          if (status != expected) {
            violations.add(
                operation.operationId() + " " + caller.name() + " not listed but got " + status);
          }
        }
        if (caller.role() != null && reloginNeeded(operation, status)) {
          int index = callers.indexOf(caller);
          callers.set(
              index,
              new Caller(
                  caller.name(),
                  caller.role(),
                  Set.of(),
                  csrf(issueSession(accounts.get(caller.role())).bearer())));
        }
      }
    }
    matrix.append(String.format("  {\"total_calls\": %d}%n]}%n", calls));
    Instances.record("M7-authorisation-matrix.json", matrix.toString());
    assertThat(violations).isEmpty();
    assertThat(calls).isGreaterThan(policy.operations().size() * 8);
  }

  private static boolean listed(Access access, Caller caller) {
    if (access.isPublic() || access.refreshCookie()) {
      return true;
    }
    if (!access.roles().isEmpty()) {
      return caller.role() != null && access.roles().contains(caller.role());
    }
    return caller.scopes().stream().anyMatch(access.scopes()::contains);
  }

  /**
   * A listed caller may still be refused by the operation itself: the refresh operation's
   * credential is the refresh cookie, which the matrix callers do not send.
   */
  private static boolean legitimateRefusal(
      ContractPolicy.Operation operation, Caller caller, Http.Response response) {
    return operation.access().refreshCookie() && response.status() == 401;
  }

  private static boolean reloginNeeded(ContractPolicy.Operation operation, int status) {
    return status < 300
        && Set.of("logout", "logoutEverywhere", "changePassword").contains(operation.operationId());
  }

  private Http.Response call(ContractPolicy.Operation operation, Caller caller) {
    String path =
        operation
            .path()
            .replace("{account_token}", "tok_" + "A".repeat(24))
            .replace("{key_id}", "abcdefghijkl")
            .replace("{model_version}", "v1")
            .replace("{token}", "t".repeat(32))
            .replaceAll("\\{[a-z_]+}", UUID.randomUUID().toString());
    boolean hasBody = Set.of("POST", "PUT", "PATCH").contains(operation.method());
    return http.send(operation.method(), path, hasBody ? "{}" : null, caller.headers());
  }

  /**
   * Adds a matching CSRF header and cookie, so the double-submit check never masks authorisation.
   */
  private static String[] csrf(String... headers) {
    String token = "m".repeat(43);
    String[] all = java.util.Arrays.copyOf(headers, headers.length + 4);
    all[headers.length] = "X-CSRF-Token";
    all[headers.length + 1] = token;
    all[headers.length + 2] = "Cookie";
    all[headers.length + 3] = "fs_csrf=" + token;
    return all;
  }
}
