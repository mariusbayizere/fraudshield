package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FR-01-07: the OpenAPI 3.1 document is served at {@code /api/docs}.
 *
 * <p>The point of this test is the first assertion: the served bytes are {@code
 * contracts/openapi/fraudshield-api.yaml}, byte for byte. A document generated from the code, or a
 * copy kept by hand, could describe an API that does not exist; this one cannot drift from the
 * contract integrators build against, and if anyone makes it drift, this fails.
 */
@Tag("requires-docker")
@Tag("FR-01-07")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(ApiHarness.Collaborators.class)
@org.springframework.test.context.ActiveProfiles(ApiHarness.PROFILE)
class ApiDocsTest {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Path CONTRACT =
      Path.of("..", "..", "contracts", "openapi", "fraudshield-api.yaml");

  @Autowired Environment environment;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
  }

  private HttpResponse<byte[]> get(String path, String key) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
            .GET();
    if (key != null) {
      request.header("X-API-Key", key);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void theServedDocumentIsTheFrozenContractByteForByte() throws Exception {
    HttpResponse<byte[]> response = get("/api/docs", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(Files.readAllBytes(CONTRACT));
    assertThat(response.headers().firstValue("Content-Type")).contains("application/yaml");
    assertThat(response.headers().firstValue("ETag")).isPresent();
    assertThat(get("/api/docs/openapi.yaml", null).body())
        .as("the same document under its file name")
        .isEqualTo(response.body());
  }

  @Test
  void theDocumentIsOpenApiThreeOneAndCarriesAnExampleForEveryChannel() throws Exception {
    String document = new String(get("/api/docs", null).body(), StandardCharsets.UTF_8);
    assertThat(document).startsWith("openapi: 3.1");
    for (String channel :
        List.of("MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER")) {
      assertThat(document).as(channel).contains(channel);
    }
    // Paths are relative to the document's server, which is /api/v1.
    assertThat(document).contains("url: /api/v1").contains("/transactions/ingest:");
  }

  @Test
  void theContractNeedsNoKeyAndIsUnchangedWithOne() throws Exception {
    // It is the public contract: an integrator reads it before they have a key.
    HttpResponse<byte[]> anonymous = get("/api/docs", null);
    HttpResponse<byte[]> authenticated = get("/api/docs", ApiHarness.KEY);
    assertThat(anonymous.statusCode()).isEqualTo(200);
    assertThat(authenticated.statusCode()).isEqualTo(200);
    assertThat(authenticated.body()).isEqualTo(anonymous.body());
  }
}
