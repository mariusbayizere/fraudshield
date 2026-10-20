package io.github.mariusbayizere.fraudshield.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.web.SessionCookies;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The server's cookie and header names are the ones the packaged contract names (D-27). The console
 * asserts the same values against the same contract, so the two cannot drift apart unnoticed.
 */
@Tag("D-27")
class CsrfCookieContractTest {

  @Test
  void theCsrfCookieAndHeaderAreTheContractsNames() throws IOException {
    Map<String, Object> components = section(contract(), "components");
    Map<String, Object> csrf = section(section(components, "parameters"), "CsrfToken");

    assertThat(csrf.get("x-csrf-cookie")).isEqualTo(CsrfDoubleSubmitFilter.COOKIE);
    assertThat(csrf.get("name")).isEqualTo(CsrfDoubleSubmitFilter.HEADER);
    assertThat(csrf.get("in")).isEqualTo("header");
  }

  @Test
  void theRefreshCookieIsTheContractsName() throws IOException {
    Map<String, Object> components = section(contract(), "components");
    Map<String, Object> refresh = section(section(components, "securitySchemes"), "refreshCookie");

    assertThat(refresh.get("name")).isEqualTo(SessionCookies.REFRESH);
    assertThat(refresh.get("in")).isEqualTo("cookie");
  }

  private static Map<String, Object> contract() throws IOException {
    ClassLoader loader = CsrfCookieContractTest.class.getClassLoader();
    try (InputStream in =
        Objects.requireNonNull(
            loader.getResourceAsStream(ContractPolicy.OPENAPI), ContractPolicy.OPENAPI)) {
      return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> parent, String key) {
    return (Map<String, Object>) Objects.requireNonNull(parent.get(key), key);
  }
}
