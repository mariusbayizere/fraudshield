package io.github.mariusbayizere.fraudshield.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SSRF guard of ApiKeyCreate.webhook_url, with DNS answers fixed by the test. */
class WebhookUrlValidatorTest {

  private static final Map<String, String> DNS =
      Map.ofEntries(
          Map.entry("hooks.bank.example", "203.0.113.10"),
          Map.entry("v6.bank.example", "2001:4860:4860::8888"),
          Map.entry("internal.bank.example", "10.1.2.3"),
          Map.entry("metadata.bank.example", "169.254.169.254"),
          Map.entry("cgnat.bank.example", "100.64.0.1"),
          Map.entry("loop.bank.example", "127.0.0.1"),
          Map.entry("ula.bank.example", "fd00::1"),
          Map.entry("mapped.bank.example", "::ffff:192.168.1.1"),
          Map.entry("multicast.bank.example", "224.0.0.1"),
          Map.entry("zero.bank.example", "0.0.0.0"),
          Map.entry("private172.bank.example", "172.16.5.4"));

  private static InetAddress[] resolve(String host) throws UnknownHostException {
    String address = DNS.get(host);
    if (address == null) {
      throw new UnknownHostException(host);
    }
    return new InetAddress[] {InetAddress.getByName(address)};
  }

  private final WebhookUrlValidator validator =
      new WebhookUrlValidator(WebhookUrlValidatorTest::resolve);

  @ParameterizedTest
  @ValueSource(
      strings = {"https://hooks.bank.example/fraudshield", "https://v6.bank.example:8443/h"})
  void allowsHttpsOnPublicHostNames(String url) {
    assertThat(validator.allowed(url)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://hooks.bank.example/insecure",
        "https://user:pass@hooks.bank.example/",
        "https://203.0.113.10/literal",
        "https://[2001:db8::1]/literal",
        "https://internal.bank.example/",
        "https://metadata.bank.example/latest/meta-data",
        "https://cgnat.bank.example/",
        "https://loop.bank.example/",
        "https://ula.bank.example/",
        "https://mapped.bank.example/",
        "https://multicast.bank.example/",
        "https://zero.bank.example/",
        "https://private172.bank.example/",
        "https://unresolvable.bank.example/",
        "not a url",
        "https:///nohost"
      })
  void refusesEverythingElse(String url) {
    assertThat(validator.allowed(url)).isFalse();
  }
}
