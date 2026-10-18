package io.github.mariusbayizere.fraudshield.auth.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small HTTP client for the running test application. */
public final class Http {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String baseUrl;

  /**
   * Creates the client.
   *
   * @param baseUrl http://localhost:port
   */
  public Http(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /**
   * A response.
   *
   * @param status status
   * @param headers headers
   * @param body body text
   */
  public record Response(int status, Map<String, List<String>> headers, String body) {

    /**
     * The body as JSON.
     *
     * @return the parsed body
     */
    public JsonNode json() {
      return JSON.readTree(body);
    }

    /**
     * A header's first value.
     *
     * @param name header name (any case)
     * @return the value
     */
    public Optional<String> header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .flatMap(e -> e.getValue().stream())
          .findFirst();
    }

    /**
     * Every Set-Cookie value.
     *
     * @return the cookies
     */
    public List<String> setCookies() {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase("set-cookie"))
          .flatMap(e -> e.getValue().stream())
          .toList();
    }

    /**
     * The value of a cookie set by this response.
     *
     * @param name cookie name
     * @return its value
     */
    public Optional<String> cookie(String name) {
      return setCookies().stream()
          .filter(c -> c.startsWith(name + "="))
          .map(
              c -> c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
          .findFirst();
    }

    /**
     * The problem type URN of a problem response.
     *
     * @return the type
     */
    public String problemType() {
      return json().get("type").asString();
    }
  }

  /**
   * Sends a request.
   *
   * @param method method
   * @param path path
   * @param body JSON body, or null
   * @param headers header name-value pairs
   * @return the response
   */
  public Response send(String method, String path, Object body, String... headers) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
    for (int i = 0; i < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      String text = body instanceof String s ? s : JSON.writeValueAsString(body);
      builder.method(method, HttpRequest.BodyPublishers.ofString(text));
    }
    try {
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.headers().map(), response.body());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /**
   * GET.
   *
   * @param path path
   * @param headers headers
   * @return the response
   */
  public Response get(String path, String... headers) {
    return send("GET", path, null, headers);
  }

  /**
   * POST with a JSON body.
   *
   * @param path path
   * @param body body
   * @param headers headers
   * @return the response
   */
  public Response post(String path, Object body, String... headers) {
    return send("POST", path, body, headers);
  }
}
