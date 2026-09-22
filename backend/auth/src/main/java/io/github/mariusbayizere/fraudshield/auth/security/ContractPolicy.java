package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The URL-level authorisation policy, read at startup from the frozen contract packaged with this
 * module: the OpenAPI document gives each operation's method and path, the golden matrix gives who
 * may call it (ADR 0014). Nothing is restated in Java, so the running filter cannot drift from the
 * contract; controllers add method-level rules that {@code AuthorisationMatrixTest} checks against
 * the same matrix.
 *
 * <p>Deny by default: a request matching no operation is refused. Operations served on the
 * management port ({@code x-network: management}) are not part of this API and are omitted.
 */
public final class ContractPolicy {

  /** Classpath location of the packaged OpenAPI document. */
  public static final String OPENAPI = "fraudshield/contracts/fraudshield-api.yaml";

  /** Classpath location of the packaged authorisation matrix. */
  public static final String MATRIX = "fraudshield/contracts/authorisation-matrix.yaml";

  private static final String API_BASE = "/api/v1";
  private static final Map<String, String> HTTP_METHODS =
      Map.of("get", "GET", "put", "PUT", "post", "POST", "delete", "DELETE", "patch", "PATCH");
  private static final String CSRF_PARAMETER = "#/components/parameters/CsrfToken";

  /**
   * One operation.
   *
   * @param operationId contract operation ID
   * @param method HTTP method, upper case
   * @param path full path template, for example {@code /api/v1/admin/users/{user_id}}
   * @param access who may call it
   * @param requiresCsrf whether it takes the double-submit CSRF header (D-27)
   */
  public record Operation(
      String operationId, String method, String path, Access access, boolean requiresCsrf) {}

  private record Matcher(Operation operation, PathPattern pattern) {}

  private final List<Matcher> matchers;
  private final Map<String, Operation> byId;

  private ContractPolicy(List<Operation> operations) {
    PathPatternParser parser = new PathPatternParser();
    List<Matcher> list = new ArrayList<>();
    Map<String, Operation> ids = new LinkedHashMap<>();
    for (Operation operation : operations) {
      list.add(new Matcher(operation, parser.parse(operation.path())));
      ids.put(operation.operationId(), operation);
    }
    // Most specific first: a literal segment beats a variable at the same position.
    list.sort(Comparator.comparing(Matcher::pattern, PathPattern.SPECIFICITY_COMPARATOR));
    this.matchers = List.copyOf(list);
    this.byId = Map.copyOf(ids);
  }

  /**
   * Loads the policy packaged on the classpath.
   *
   * @return the policy
   */
  public static ContractPolicy load() {
    ClassLoader loader = ContractPolicy.class.getClassLoader();
    try (InputStream openapi =
            Objects.requireNonNull(loader.getResourceAsStream(OPENAPI), OPENAPI);
        InputStream matrix = Objects.requireNonNull(loader.getResourceAsStream(MATRIX), MATRIX)) {
      return parse(openapi, matrix);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read the packaged contract", e);
    }
  }

  /**
   * Parses a policy.
   *
   * @param openapiYaml OpenAPI document
   * @param matrixYaml authorisation matrix
   * @return the policy
   */
  @SuppressWarnings("unchecked")
  public static ContractPolicy parse(InputStream openapiYaml, InputStream matrixYaml) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Object> openapi = yaml.load(openapiYaml);
    Map<String, Object> matrix =
        (Map<String, Object>) ((Map<String, Object>) yaml.load(matrixYaml)).get("operations");
    Map<String, Object> paths = (Map<String, Object>) openapi.get("paths");
    List<Operation> operations = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Map.Entry<String, Object> path : paths.entrySet()) {
      Map<String, Object> item = (Map<String, Object>) path.getValue();
      Optional<String> base = base(item);
      for (Map.Entry<String, Object> entry : item.entrySet()) {
        if (!HTTP_METHODS.containsKey(entry.getKey())) {
          continue;
        }
        Map<String, Object> operation = (Map<String, Object>) entry.getValue();
        String id = (String) operation.get("operationId");
        Map<String, Object> declared = (Map<String, Object>) matrix.get(id);
        if (declared == null) {
          throw new IllegalStateException(
              "operation " + id + " is missing from the authorisation matrix");
        }
        seen.add(id);
        if (base.isEmpty() || "management".equals(operation.get("x-network"))) {
          continue;
        }
        operations.add(
            new Operation(
                id,
                HTTP_METHODS.get(entry.getKey()),
                base.get() + path.getKey(),
                access(id, declared),
                takesCsrf(operation)));
      }
    }
    if (!seen.containsAll(matrix.keySet())) {
      Set<String> extra = new HashSet<>(matrix.keySet());
      extra.removeAll(seen);
      throw new IllegalStateException("the authorisation matrix names unknown operations " + extra);
    }
    return new ContractPolicy(operations);
  }

  @SuppressWarnings("unchecked")
  private static Optional<String> base(Map<String, Object> pathItem) {
    Object servers = pathItem.get("servers");
    if (servers == null) {
      return Optional.of(API_BASE);
    }
    String url = (String) ((Map<String, Object>) ((List<Object>) servers).getFirst()).get("url");
    if (url.equals("/")) {
      return Optional.of("");
    }
    return Optional.empty(); // another host or port, such as the management port
  }

  @SuppressWarnings("unchecked")
  private static Access access(String id, Map<String, Object> declared) {
    Set<StaffRole> roles = new HashSet<>();
    for (Object role : (List<Object>) declared.getOrDefault("roles", List.of())) {
      roles.add(StaffRole.valueOf((String) role));
    }
    Set<ApiKeyScope> scopes = new HashSet<>();
    for (Object scope : (List<Object>) declared.getOrDefault("scopes", List.of())) {
      scopes.add(
          ApiKeyScope.parse((String) scope)
              .orElseThrow(
                  () -> new IllegalStateException(id + " names an unknown scope " + scope)));
    }
    return new Access(
        roles,
        scopes,
        Boolean.TRUE.equals(declared.get("public")),
        Boolean.TRUE.equals(declared.get("refresh-cookie")));
  }

  @SuppressWarnings("unchecked")
  private static boolean takesCsrf(Map<String, Object> operation) {
    for (Object parameter : (List<Object>) operation.getOrDefault("parameters", List.of())) {
      if (parameter instanceof Map<?, ?> map && CSRF_PARAMETER.equals(map.get("$ref"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The operation a request addresses.
   *
   * @param method HTTP method
   * @param path request path within the servlet context
   * @return the operation, or empty if the contract has none (deny)
   */
  public Optional<Operation> match(String method, String path) {
    PathContainer container = PathContainer.parsePath(path);
    for (Matcher matcher : matchers) {
      if (matcher.operation().method().equals(method) && matcher.pattern().matches(container)) {
        return Optional.of(matcher.operation());
      }
    }
    return Optional.empty();
  }

  /**
   * Every operation of this API.
   *
   * @return operations by ID
   */
  public Map<String, Operation> operations() {
    return byId;
  }
}
