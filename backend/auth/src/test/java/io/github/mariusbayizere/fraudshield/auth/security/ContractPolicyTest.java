package io.github.mariusbayizere.fraudshield.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The URL policy is read from the packaged contract and matches requests correctly (ADR 0014). */
@Tag("FR-07-01")
class ContractPolicyTest {

  private final ContractPolicy policy = ContractPolicy.load();

  @Test
  void loadsEveryApplicationPortOperationAndSkipsTheManagementPort() {
    assertThat(policy.operations())
        .containsKeys("login", "listUsers", "ingestTransaction", "showVerificationPage");
    assertThat(policy.operations()).doesNotContainKeys("getLiveness", "getMlComponentHealth");
    assertThat(policy.operations().get("showVerificationPage").path()).isEqualTo("/verify/{token}");
    assertThat(policy.operations().get("listUsers").path()).isEqualTo("/api/v1/admin/users");
  }

  @Test
  void matchesMethodAndPathWithLiteralSegmentsFirst() {
    assertThat(policy.match("GET", "/api/v1/admin/users/2d1f0f5e-7c1b-4c2a-9d57-3a1c9a4b2e10"))
        .hasValueSatisfying(op -> assertThat(op.operationId()).isEqualTo("getUser"));
    assertThat(policy.match("PATCH", "/api/v1/admin/users/2d1f0f5e-7c1b-4c2a-9d57-3a1c9a4b2e10"))
        .hasValueSatisfying(op -> assertThat(op.operationId()).isEqualTo("updateUser"));
    assertThat(policy.match("POST", "/api/v1/rules/preview"))
        .hasValueSatisfying(op -> assertThat(op.operationId()).isEqualTo("previewRule"));
    assertThat(policy.match("DELETE", "/api/v1/admin/users")).isEmpty();
    assertThat(policy.match("GET", "/api/v1/nothing-here")).isEmpty();
  }

  @Test
  void accessComesFromTheMatrix() {
    assertThat(policy.operations().get("listUsers").access().roles())
        .containsExactly(StaffRole.ADMIN);
    assertThat(policy.operations().get("ingestTransaction").access().scopes())
        .containsExactly(ApiKeyScope.INGEST_WRITE);
    assertThat(policy.operations().get("login").access().isPublic()).isTrue();
    assertThat(policy.operations().get("refreshToken").access().refreshCookie()).isTrue();
    assertThat(policy.operations().get("decideAlert").access().roles())
        .containsExactlyInAnyOrder(
            StaffRole.ANALYST, StaffRole.SENIOR_ANALYST, StaffRole.RISK_OFFICER);
  }

  @Test
  @Tag("D-27")
  void csrfIsRequiredExactlyWhereTheContractDeclaresIt() {
    assertThat(
            policy.operations().values().stream()
                .filter(ContractPolicy.Operation::requiresCsrf)
                .map(ContractPolicy.Operation::operationId))
        .containsExactlyInAnyOrder("refreshToken", "logout", "logoutEverywhere", "changePassword");
  }

  @Test
  void refusesContractsWhereMatrixAndOpenApiDisagree() {
    String openapi =
        """
        paths:
          /x:
            get: { operationId: onlyInOpenApi }
        """;
    String matrix = "operations:\n  other: { public: true }\n";
    assertThatThrownBy(() -> parse(openapi, matrix)).hasMessageContaining("onlyInOpenApi");
    String matching = "operations:\n  onlyInOpenApi: { public: true }\n  extra: { public: true }\n";
    assertThatThrownBy(() -> parse(openapi, matching)).hasMessageContaining("extra");
  }

  @Test
  void accessDeclaresExactlyOneKind() {
    assertThatThrownBy(() -> new Access(Set.of(StaffRole.ADMIN), Set.of(), true, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Access(Set.of(), Set.of(), false, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ContractPolicy parse(String openapi, String matrix) {
    return ContractPolicy.parse(
        new ByteArrayInputStream(openapi.getBytes(StandardCharsets.UTF_8)),
        new ByteArrayInputStream(matrix.getBytes(StandardCharsets.UTF_8)));
  }
}
