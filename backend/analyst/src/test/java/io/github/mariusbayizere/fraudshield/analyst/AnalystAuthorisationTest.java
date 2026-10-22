package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.security.ContractPolicy;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * Every analyst handler names its contract operation, serves the contract's method and path, and
 * declares exactly the matrix's roles, as M7's {@code AuthorisationMatrixTest} holds the
 * administration API to (ADR 0014). ADMIN takes no part in alerts.
 */
@Tag("TEST-04")
class AnalystAuthorisationTest extends AnalystIntegrationTest {

  private static final Pattern ROLE_RULE =
      Pattern.compile("^hasAnyRole\\(((?:'[A-Z_]+'(?:, )?)+)\\)$");
  private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

  @Autowired private ContractPolicy policy;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  void everyAnalystHandlerMatchesTheContractAndTheMatrix() {
    List<String> problems = new ArrayList<>();
    Set<String> implemented = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        mappings.getHandlerMethods().entrySet()) {
      Method method = entry.getValue().getMethod();
      if (!method
          .getDeclaringClass()
          .getPackageName()
          .startsWith("io.github.mariusbayizere.fraudshield.analyst")) {
        continue;
      }
      String where = method.getDeclaringClass().getSimpleName() + "." + method.getName();
      ContractOperation operation = method.getAnnotation(ContractOperation.class);
      PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
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
      String expected =
          "roles:"
              + contract.access().roles().stream()
                  .map(Enum::name)
                  .sorted()
                  .collect(Collectors.joining(","));
      if (!normalise(rule.value()).equals(expected)) {
        problems.add(where + " has " + rule.value() + " but the matrix says " + expected);
      }
    }
    assertThat(problems).isEmpty();
    assertThat(implemented)
        .as("P1's seven reads (ADR 0110) and P2's four decisions (ADR 0114)")
        .containsExactlyInAnyOrder(
            "listAlerts",
            "getAlert",
            "getAlertExplanation",
            "getScoringResult",
            "listAccountTransactions",
            "getBehaviourProfile",
            "getAccountNetwork",
            "decideAlert",
            "undoAlertDecision",
            "escalateAlert",
            "overrideAlertDecision");
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
}
