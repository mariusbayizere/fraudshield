package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * NFR-SEC-03's inspector (PB-114): every analyst endpoint is called on seeded data, and every
 * response is walked for raw personal data. Account identifiers must be opaque tokens, the masked
 * account must be the SMS's masked form or absent, and no value may look like a phone number, an
 * e-mail address or an account number. A new analyst endpoint without a case here fails the test,
 * so the inspection cannot fall behind the API.
 */
@Tag("NFR-SEC-03")
class AnalystInspectorTest extends AnalystIntegrationTest {

  private static final UUID BANK = DB.createInstitution("analyst-inspector");

  private static final Pattern TOKEN = Pattern.compile("^tok_[A-Za-z0-9]{24,64}$");
  private static final Pattern MASKED = Pattern.compile("^\\*{3,}[0-9A-Za-z]{2,4}$");
  private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
  private static final Pattern PHONE_OR_ACCOUNT = Pattern.compile("^\\+?[0-9][0-9 -]{7,}[0-9]$");
  private static final Set<String> TOKEN_FIELDS =
      Set.of("account_token", "counterparty_token", "token", "source", "target");
  private static final Set<String> CUSTOMER_PII_FIELDS =
      Set.of(
          "account_name",
          "masked_account_name",
          "customer_name",
          "full_name",
          "msisdn",
          "phone",
          "phone_number",
          "national_id",
          "account_number",
          "email");

  /**
   * Amounts and amount ranges, which are decimal strings (or "low-high") and may be long without
   * being identifiers.
   */
  private static final Set<String> AMOUNT_FIELDS =
      Set.of("amount", "expected_loss_rwf", "lower_rwf", "upper_rwf", "this_transaction", "normal");

  /** ISO dates of the performance figures (ADR 0117), which are digits and hyphens. */
  private static final Set<String> DATE_FIELDS = Set.of("date", "from", "to", "agreement_from");

  private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  void everyAnalystResponseCarriesTokensAndMaskedValuesOnly() {
    Tx tx = new Tx(BANK);
    tx.device = token();
    tx.merchant = "Kigali Fresh Market";
    tx.write();
    final Alert alert = new Alert(tx).write();
    Tx earlier = new Tx(BANK);
    earlier.account = tx.account;
    earlier.at = tx.at.minusSeconds(86_400);
    earlier.write();

    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("listAlerts", "/api/v1/alerts?queue=all");
    cases.put("getAlert", "/api/v1/alerts/" + alert.id);
    cases.put("getAlertExplanation", "/api/v1/alerts/" + alert.id + "/explanation");
    cases.put("getScoringResult", "/api/v1/transactions/" + tx.id + "/scoring-result");
    cases.put("listAccountTransactions", "/api/v1/accounts/" + tx.account + "/transactions");
    cases.put(
        "getBehaviourProfile",
        "/api/v1/accounts/" + tx.account + "/behaviour-profile?transaction_id=" + tx.id);
    cases.put("getAccountNetwork", "/api/v1/accounts/" + tx.account + "/network");
    cases.put("getDashboardStats", "/api/v1/dashboard/stats");
    cases.put("getMyPerformance", "/api/v1/me/performance");
    cases.put("getTeamPerformance", "/api/v1/team/performance");
    cases.put("listAssignableStaff", "/api/v1/team/assignees?purpose=filter");

    assertThat(cases.keySet())
        .as("every analyst endpoint has an inspector case")
        .containsExactlyInAnyOrderElementsOf(analystOperations());

    Session session = issueSession(createAccount(BANK, "ANALYST", "ACTIVE"));
    // The team table (ADR 0117 point 7) and the assignee list (ADR 0116) are for seniors and risk
    // officers; the assignee list must never carry e-mail.
    Session senior = issueSession(createAccount(BANK, "SENIOR_ANALYST", "ACTIVE"));
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, String> entry : cases.entrySet()) {
      Session caller =
          "getTeamPerformance".equals(entry.getKey())
                  || "listAssignableStaff".equals(entry.getKey())
              ? senior
              : session;
      Http.Response response = http.get(entry.getValue(), caller.bearer());
      assertThat(response.status()).as(entry.getKey() + " " + response.body()).isEqualTo(200);
      inspect(entry.getKey(), "", null, response.json(), problems);
    }
    assertThat(problems).isEmpty();
  }

  /**
   * The operation ids of every read this module serves. P2's writes (decide, undo, escalate,
   * override) answer with closed schemas whose fields carry no customer data, which
   * AnalystDecisionsTest validates; the reads are where account data could leak.
   */
  private Set<String> analystOperations() {
    Set<String> operations = new TreeSet<>();
    for (Map.Entry<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod>
        entry : mappings.getHandlerMethods().entrySet()) {
      HandlerMethod handler = entry.getValue();
      boolean read =
          entry
              .getKey()
              .getMethodsCondition()
              .getMethods()
              .contains(org.springframework.web.bind.annotation.RequestMethod.GET);
      if (read
          && handler
              .getBeanType()
              .getPackageName()
              .startsWith("io.github.mariusbayizere.fraudshield.analyst")) {
        ContractOperation operation = handler.getMethod().getAnnotation(ContractOperation.class);
        operations.add(operation == null ? handler.toString() : operation.value());
      }
    }
    return operations;
  }

  /**
   * Inspects one response body; the decision operations' tests call it on their bodies too.
   *
   * @param operation the operation, for messages
   * @param path the JSON path so far
   * @param field the member's name, or null
   * @param node the node
   * @param problems where problems are added
   */
  static void inspect(
      String operation, String path, String field, JsonNode node, List<String> problems) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> member : node.properties()) {
        String name = member.getKey();
        String at = path + "/" + name;
        if (CUSTOMER_PII_FIELDS.contains(name)) {
          problems.add(operation + at + ": a customer PII field");
        }
        if ("masked_account".equals(name)
            && !member.getValue().isNull()
            && !MASKED.matcher(member.getValue().asString()).matches()) {
          problems.add(operation + at + ": not a masked account");
        }
        inspect(operation, at, name, member.getValue(), problems);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        inspect(operation, path + "/" + i, field, node.get(i), problems);
      }
    } else if (node.isString()) {
      String value = node.asString();
      if (field != null && TOKEN_FIELDS.contains(field) && !TOKEN.matcher(value).matches()) {
        problems.add(operation + path + ": an account identifier that is not a token");
      }
      if (EMAIL.matcher(value).find()) {
        problems.add(operation + path + ": looks like an e-mail address");
      }
      boolean date = DATE_FIELDS.contains(field) && DATE.matcher(value).matches();
      if (!AMOUNT_FIELDS.contains(field) && !date && PHONE_OR_ACCOUNT.matcher(value).matches()) {
        problems.add(operation + path + ": looks like a phone or account number");
      }
    }
  }
}
