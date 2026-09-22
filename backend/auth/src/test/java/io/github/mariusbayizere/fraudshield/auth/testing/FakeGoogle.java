package io.github.mariusbayizere.fraudshield.auth.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A local stand-in for Google's token, JWKS and revocation endpoints (D-51 local fake): tests set
 * the identity the next code exchange returns and read which tokens were revoked.
 */
public final class FakeGoogle implements AutoCloseable {

  /** OAuth client ID the fake issues ID tokens for. */
  public static final String CLIENT_ID = "fraudshield-test.apps.googleusercontent.com";

  /** Redirect URI registered for tests. */
  public static final String REDIRECT_URI = "https://console.test.fraudshield.local/auth/google";

  private final HttpServer server;
  private final RSAKey signingKey;
  private final RSAKey foreignKey;
  private final AtomicReference<Map<String, Object>> nextIdentity = new AtomicReference<>();
  private final AtomicReference<String> nextAudience = new AtomicReference<>(CLIENT_ID);
  private final AtomicInteger tokenStatus = new AtomicInteger(200);
  private final AtomicReference<Boolean> signWithForeignKey = new AtomicReference<>(false);
  private final List<String> revoked = new CopyOnWriteArrayList<>();
  private final AtomicInteger issued = new AtomicInteger();

  /**
   * Starts the fake on a free port.
   *
   * @throws IOException if the server cannot start
   */
  public FakeGoogle() throws IOException {
    try {
      signingKey = new RSAKeyGenerator(2048).keyID("google-test-1").generate();
      foreignKey = new RSAKeyGenerator(2048).keyID("google-test-1").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext(
        "/jwks",
        exchange -> respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString()));
    server.createContext("/revoke", this::revoke);
    server.start();
  }

  /**
   * Base URL of the fake.
   *
   * @return http://127.0.0.1:port
   */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Sets the identity of the next exchange.
   *
   * @param sub subject
   * @param email email
   * @param emailVerified email_verified claim
   * @param givenName given name
   * @param familyName family name
   */
  public void nextIdentity(
      String sub, String email, boolean emailVerified, String givenName, String familyName) {
    nextIdentity.set(
        Map.of(
            "sub", sub,
            "email", email,
            "email_verified", emailVerified,
            "given_name", givenName,
            "family_name", familyName,
            "picture", "https://lh3.googleusercontent.com/a/" + sub));
    nextAudience.set(CLIENT_ID);
    tokenStatus.set(200);
    signWithForeignKey.set(false);
  }

  /**
   * Makes the next ID token carry another audience.
   *
   * @param audience the audience
   */
  public void nextAudience(String audience) {
    nextAudience.set(audience);
  }

  /**
   * Makes the token endpoint answer a status (400 refuses the code, 503 is an outage).
   *
   * @param status HTTP status
   */
  public void tokenStatus(int status) {
    tokenStatus.set(status);
  }

  /** Makes the next ID token be signed by a key Google never published. */
  public void signWithForeignKey() {
    signWithForeignKey.set(true);
  }

  /**
   * Access tokens revoked so far.
   *
   * @return the tokens
   */
  public List<String> revoked() {
    return List.copyOf(revoked);
  }

  private void token(HttpExchange exchange) throws IOException {
    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (tokenStatus.get() != 200
        || !form.contains("code_verifier=")
        || !form.contains("client_secret=")) {
      respond(
          exchange,
          tokenStatus.get() == 200 ? 400 : tokenStatus.get(),
          "{\"error\":\"invalid_grant\"}");
      return;
    }
    Map<String, Object> identity = nextIdentity.get();
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer("https://accounts.google.com")
            .audience(nextAudience.get())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)));
    identity.forEach(claims::claim);
    claims.subject((String) identity.get("sub"));
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("google-test-1").build(),
            claims.build());
    try {
      jwt.sign(new RSASSASigner(signWithForeignKey.get() ? foreignKey : signingKey));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    String accessToken = "ya29.test-access-token-" + issued.incrementAndGet();
    respond(
        exchange,
        200,
        "{\"id_token\":\""
            + jwt.serialize()
            + "\",\"access_token\":\""
            + accessToken
            + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
  }

  private void revoke(HttpExchange exchange) throws IOException {
    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    for (String pair : form.split("&")) {
      if (pair.startsWith("token=")) {
        revoked.add(URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8));
      }
    }
    respond(exchange, 200, "");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
