package io.github.mariusbayizere.fraudshield.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyStoreUnavailableException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * What the ingestion filter answers when the key cannot be checked
 * (docs/architecture/api-key-cache-contract.md §3; threat model R-13).
 */
class ApiKeyFilterTest {

  private static MockHttpServletResponse send(ApiKeyAuthenticator keys) throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/transactions/ingest");
    request.addHeader("X-API-Key", "fsk_test_aaaaaaaaaaaa_" + "k".repeat(43));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean passedOn = new AtomicBoolean();
    new ApiKeyFilter(keys).doFilter(request, response, (req, res) -> passedOn.set(true));
    assertThat(passedOn).isFalse();
    return response;
  }

  @Test
  void keyStoreThatCannotAnswerIsServiceUnavailableNotUnauthorized() throws Exception {
    // In the assembled API the staff module's filter answers first; this one checks the key again,
    // and its cache entry can expire in between while PostgreSQL is down (P28).
    MockHttpServletResponse response =
        send(
            raw -> {
              throw new ApiKeyStoreUnavailableException(new IllegalStateException("down"));
            });

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString()).contains("service-unavailable");
  }

  @Test
  void anyOtherErrorIsAnInternalErrorNotAnOutage() throws Exception {
    MockHttpServletResponse response =
        send(
            raw -> {
              throw new BadSqlGrammarException(
                  "q", "select", new java.sql.SQLException("syntax", "42601"));
            });

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getHeader("Retry-After")).isNull();
    assertThat(response.getContentAsString()).contains("urn:fraudshield:problem:internal");
  }
}
