package io.github.mariusbayizere.fraudshield.auth.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google's token and revocation endpoints over HTTPS with a hard timeout per call (H.1: every
 * external call has a timeout). Codes are exchanged once, so no retry is attempted.
 */
public final class HttpGoogleIdentityClient implements GoogleIdentityClient {

  private static final Logger LOG = LoggerFactory.getLogger(HttpGoogleIdentityClient.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int OK = 200;

  private final HttpClient http;
  private final URI tokenUri;
  private final URI revokeUri;
  private final String clientId;
  private final String clientSecret;
  private final Duration timeout;

  /**
   * Creates the client.
   *
   * @param tokenUri token endpoint
   * @param revokeUri revocation endpoint
   * @param clientId OAuth client ID
   * @param clientSecret OAuth client secret
   * @param timeout per-call timeout
   */
  public HttpGoogleIdentityClient(
      URI tokenUri, URI revokeUri, String clientId, String clientSecret, Duration timeout) {
    this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri");
    this.revokeUri = Objects.requireNonNull(revokeUri, "revokeUri");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public Optional<GoogleTokens> exchange(String code, String codeVerifier, String redirectUri) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("code_verifier", codeVerifier);
    form.put("redirect_uri", redirectUri);
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    HttpResponse<String> response;
    try {
      response = http.send(post(tokenUri, form), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new GoogleUnavailableException("the Google token endpoint is unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GoogleUnavailableException("interrupted calling Google", e);
    }
    if (response.statusCode() >= 500) {
      throw new GoogleUnavailableException("Google answered " + response.statusCode(), null);
    }
    if (response.statusCode() != OK) {
      return Optional.empty();
    }
    JsonNode body = JSON.readTree(response.body());
    JsonNode idToken = body.get("id_token");
    if (idToken == null || !idToken.isString()) {
      return Optional.empty();
    }
    JsonNode access = body.get("access_token");
    JsonNode expires = body.get("expires_in");
    return Optional.of(
        new GoogleTokens(
            idToken.asString(),
            access == null ? null : access.asString(),
            expires == null ? 0 : expires.asLong()));
  }

  @Override
  public boolean revoke(String token) {
    try {
      HttpResponse<Void> response =
          http.send(
              post(revokeUri, Map.of("token", token)), HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == OK;
    } catch (IOException e) {
      LOG.warn("Google token revocation failed: {}", e.getClass().getSimpleName());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private HttpRequest post(URI uri, Map<String, String> form) {
    String body =
        form.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }
}
