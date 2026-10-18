package io.github.mariusbayizere.fraudshield.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiScope;
import io.github.mariusbayizere.fraudshield.ingest.ratelimit.RateLimiter;
import jakarta.servlet.ServletInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * ADR 0058 point 2, review 8 of 2026-09-24: every request on the machine paths is charged one unit
 * before its body is read, so a key over its budget is refused without the server reading anything.
 * Deterministic: no clock, no network, and a body that fails the test if it is touched.
 */
@Tag("FR-01-02")
@Tag("NFR-SEC-03")
class RateLimitFilterTest {

  private static final ApiPrincipal KEY =
      new ApiPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(ApiScope.values()));

  /** A request whose body must never be read. */
  private static MockHttpServletRequest untouchable(String method, String path) {
    MockHttpServletRequest request =
        new MockHttpServletRequest(method, path) {
          @Override
          public ServletInputStream getInputStream() {
            throw new AssertionError("the body was read for a request over its budget");
          }
        };
    request.setAttribute(ApiKeyFilter.PRINCIPAL, KEY);
    return request;
  }

  @Test
  void keysOverTheirBudgetAreRefusedOnEveryMachinePathBeforeTheBodyIsRead() throws Exception {
    List<Integer> charged = new ArrayList<>();
    RateLimiter refusing =
        (key, units) -> {
          charged.add(units);
          return new RateLimiter.Permit(false, 5, 0, 1, false);
        };
    RateLimitFilter filter = new RateLimitFilter(refusing);
    for (String[] call :
        new String[][] {
          {"POST", "/api/v1/transactions/ingest/batch"},
          {"POST", "/api/v1/transactions/ingest"},
          {"GET", "/api/v1/decisions/" + UUID.randomUUID()},
          {"GET", "/api/v1/jobs/" + UUID.randomUUID()}
        }) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(untouchable(call[0], call[1]), response, chain);
      assertThat(response.getStatus()).as("%s %s", call[0], call[1]).isEqualTo(429);
      assertThat(response.getHeader("Retry-After")).isEqualTo("1");
      assertThat(chain.getRequest()).as("the controller was never reached").isNull();
    }
    assertThat(charged).as("one admission unit per request").containsOnly(1).hasSize(4);
  }

  @Test
  void keysInsideTheirBudgetPassWithOneUnitCharged() throws Exception {
    List<Integer> charged = new ArrayList<>();
    RateLimitFilter filter =
        new RateLimitFilter(
            (key, units) -> {
              charged.add(units);
              return new RateLimiter.Permit(true, 5, 4, 0, false);
            });
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/transactions/ingest/batch");
    request.setAttribute(ApiKeyFilter.PRINCIPAL, KEY);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("4");
    assertThat(charged).containsExactly(1);
  }
}
