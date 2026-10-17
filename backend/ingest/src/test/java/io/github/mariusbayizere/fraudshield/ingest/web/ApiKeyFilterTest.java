package io.github.mariusbayizere.fraudshield.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** What the ingestion filter answers when the key store cannot be read (threat model R-13). */
class ApiKeyFilterTest {

  @Test
  void keyStoreThatCannotAnswerIsServiceUnavailableNotUnauthorized() throws Exception {
    // In the assembled API the staff module's filter answers first; this one checks the key again,
    // and its cache entry can expire in between while PostgreSQL is down.
    ApiKeyFilter filter =
        new ApiKeyFilter(
            raw -> {
              throw new CannotGetJdbcConnectionException("connection refused");
            });
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/transactions/ingest");
    request.addHeader("X-API-Key", "fsk_test_aaaaaaaaaaaa_" + "k".repeat(43));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean passedOn = new AtomicBoolean();

    filter.doFilter(request, response, (req, res) -> passedOn.set(true));

    assertThat(passedOn).isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString()).contains("service-unavailable");
  }
}
