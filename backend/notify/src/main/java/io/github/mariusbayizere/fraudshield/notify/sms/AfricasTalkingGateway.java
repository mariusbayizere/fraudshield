package io.github.mariusbayizere.fraudshield.notify.sms;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Africa's Talking bulk SMS (D-51): {@code POST {base}/version1/messaging} with the application's
 * {@code apiKey} header and form fields {@code username}, {@code to}, {@code message} and {@code
 * from}. The base URL is configuration: the provider's sandbox or production host, or the local
 * WireMock stub. A recipient status other than {@code Success} is a failure. The API key is
 * configuration and never logged.
 */
public final class AfricasTalkingGateway implements SmsGateway {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient client;
  private final URI endpoint;
  private final String username;
  private final String apiKey;

  /**
   * Creates the adapter.
   *
   * @param baseUrl provider base URL, for example the sandbox host
   * @param username the Africa's Talking application username
   * @param apiKey the application API key
   */
  public AfricasTalkingGateway(String baseUrl, String username, String apiKey) {
    this.endpoint =
        URI.create(
            Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "")
                + "/version1/messaging");
    this.username = Objects.requireNonNull(username, "username");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @Override
  public String send(String senderId, String phoneE164, String text) throws IOException {
    String form =
        "username="
            + encode(username)
            + "&to="
            + encode(phoneE164)
            + "&message="
            + encode(text)
            + "&from="
            + encode(senderId);
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("apiKey", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IOException("SMS provider answered HTTP " + response.statusCode());
    }
    JsonNode recipient =
        JSON.readTree(response.body()).path("SMSMessageData").path("Recipients").path(0);
    if (!"Success".equals(recipient.path("status").asString())) {
      throw new IOException("SMS provider did not accept the message");
    }
    return recipient.path("messageId").asString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
