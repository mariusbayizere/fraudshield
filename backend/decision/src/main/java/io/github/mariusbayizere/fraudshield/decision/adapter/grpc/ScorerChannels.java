package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * Builds the channel to the scorer. mTLS is the default and required unless plaintext is enabled
 * explicitly, which only a local development profile may do (D-16: gRPC over mTLS).
 */
public final class ScorerChannels {

  /**
   * Channel settings. Certificates are passed as PEM contents, loaded by the caller from its
   * configured secret store.
   *
   * @param target host:port of the scorer
   * @param plaintext true only for local development without TLS
   * @param trustCertificatePem CA certificate that signed the scorer's certificate
   * @param clientCertificatePem this client's certificate chain
   * @param clientKeyPem this client's PKCS#8 private key
   */
  public record Settings(
      String target,
      boolean plaintext,
      byte[] trustCertificatePem,
      byte[] clientCertificatePem,
      byte[] clientKeyPem) {

    /** Requires certificates unless plaintext is explicitly enabled; copies them. */
    public Settings {
      Objects.requireNonNull(target, "target");
      if (!plaintext
          && (trustCertificatePem == null
              || clientCertificatePem == null
              || clientKeyPem == null)) {
        throw new IllegalArgumentException(
            "mTLS to the scorer needs a trust certificate, a client certificate and a key");
      }
      trustCertificatePem = copy(trustCertificatePem);
      clientCertificatePem = copy(clientCertificatePem);
      clientKeyPem = copy(clientKeyPem);
    }

    @Override
    public byte[] trustCertificatePem() {
      return copy(trustCertificatePem);
    }

    @Override
    public byte[] clientCertificatePem() {
      return copy(clientCertificatePem);
    }

    @Override
    public byte[] clientKeyPem() {
      return copy(clientKeyPem);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Settings that
          && target.equals(that.target)
          && plaintext == that.plaintext;
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, plaintext);
    }

    @Override
    public String toString() {
      return "Settings[target=" + target + ", plaintext=" + plaintext + ", keys=<redacted>]";
    }

    private static byte[] copy(byte[] bytes) {
      return bytes == null ? null : bytes.clone();
    }
  }

  private ScorerChannels() {}

  /**
   * Opens a channel.
   *
   * @param settings channel settings
   * @return a persistent HTTP/2 channel with keep-alive
   */
  public static ManagedChannel open(Settings settings) {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forTarget(settings.target())
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true);
    if (settings.plaintext()) {
      return builder.usePlaintext().build();
    }
    try {
      SslContext tls =
          GrpcSslContexts.forClient()
              .trustManager(new ByteArrayInputStream(settings.trustCertificatePem()))
              .keyManager(
                  new ByteArrayInputStream(settings.clientCertificatePem()),
                  new ByteArrayInputStream(settings.clientKeyPem()))
              .build();
      return builder.sslContext(tls).build();
    } catch (SSLException | IllegalArgumentException e) {
      throw new IllegalStateException("could not configure mTLS to the scorer", e);
    }
  }
}
