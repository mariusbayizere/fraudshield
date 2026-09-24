package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.notify.kafka.EnvelopeConsumer;
import io.github.mariusbayizere.fraudshield.notify.sms.AfricasTalkingGateway;
import io.github.mariusbayizere.fraudshield.notify.sms.ContactDirectory;
import io.github.mariusbayizere.fraudshield.notify.sms.CustomerSmsSender;
import io.github.mariusbayizere.fraudshield.notify.sms.InstitutionMessaging;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsCatalogue;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsGateway;
import io.github.mariusbayizere.fraudshield.notify.vault.AccountTokens;
import io.github.mariusbayizere.fraudshield.notify.vault.KeyProvider;
import io.github.mariusbayizere.fraudshield.notify.vault.KmsClient;
import io.github.mariusbayizere.fraudshield.notify.vault.KmsKeyProvider;
import io.github.mariusbayizere.fraudshield.notify.vault.PassphraseKeyProvider;
import io.github.mariusbayizere.fraudshield.notify.vault.VaultContacts;
import io.github.mariusbayizere.fraudshield.notify.verification.VerificationService;
import io.github.mariusbayizere.fraudshield.notify.webhook.HttpWebhookTransport;
import io.github.mariusbayizere.fraudshield.notify.webhook.RetryPolicy;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookDispatcher;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookEndpoints;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookTransport;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customer SMS and integrator webhooks. Each channel starts only when its external collaborator
 * exists: webhooks need the API-key module's endpoint registry (M7); SMS needs a PII-vault contact
 * directory and provider settings. Without them the intents stay on their topics, durable and
 * unconsumed, instead of being sent by a stand-in, and the start-up log says so.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationWiring {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationWiring.class);

  /** The running notification channels, closed with the application. */
  public static final class Channels implements AutoCloseable {
    private final List<AutoCloseable> running = new ArrayList<>();
    private WebhookDispatcher dispatcher;
    private CustomerSmsSender sender;

    /**
     * The webhook dispatcher, when webhooks are enabled.
     *
     * @return the dispatcher
     */
    public Optional<WebhookDispatcher> dispatcher() {
      return Optional.ofNullable(dispatcher);
    }

    /**
     * The SMS sender, when customer SMS is enabled.
     *
     * @return the sender
     */
    public Optional<CustomerSmsSender> sender() {
      return Optional.ofNullable(sender);
    }

    @Override
    public void close() throws Exception {
      for (AutoCloseable closeable : running.reversed()) {
        closeable.close();
      }
    }
  }

  @Bean
  InstitutionMessaging institutionMessaging(FraudShieldProperties properties) {
    Map<UUID, InstitutionMessaging.Settings> settings = new HashMap<>();
    properties
        .institutions()
        .forEach(
            (id, i) ->
                settings.put(
                    UUID.fromString(id),
                    new InstitutionMessaging.Settings(
                        i.senderId(), i.officialPhone(), i.verificationBase())));
    return institution -> Optional.ofNullable(settings.get(institution));
  }

  /**
   * The vault's key provider (D-20, ADR 0069 points 3 and 10).
   *
   * <p>{@code kms} is the default and needs a {@link KmsClient} bean, which the deployment binds to
   * its key service (M9); without one the application does not start, rather than falling back to
   * keys in configuration. {@code configured} keeps the keys in configuration and is refused unless
   * a {@code dev}, {@code demo} or {@code test} profile is active: a production deployment cannot
   * select it by accident.
   *
   * @param properties configuration
   * @param environment the active profiles
   * @param kms the key service binding, when the deployment has one
   * @return the provider
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "fraudshield.vault",
      name = "url")
  KeyProvider vaultKeys(
      FraudShieldProperties properties,
      org.springframework.core.env.Environment environment,
      ObjectProvider<KmsClient> kms) {
    return keyProvider(properties.vault(), environment, kms.getIfAvailable());
  }

  /**
   * Chooses the vault's key provider; the bean method above, without Spring.
   *
   * @param vault the vault's configuration
   * @param environment the active profiles
   * @param kms the key service binding, or null when the deployment has none
   * @return the provider
   * @throws IllegalStateException when the choice is not allowed here
   */
  static KeyProvider keyProvider(
      FraudShieldProperties.Vault vault,
      org.springframework.core.env.Environment environment,
      KmsClient kms) {
    if (FraudShieldProperties.Vault.CONFIGURED.equals(vault.keyProvider())) {
      if (!environment.matchesProfiles(CONFIGURED_KEY_PROFILES)) {
        throw new IllegalStateException(
            "fraudshield.vault.key-provider=configured holds the vault's keys in configuration and"
                + " is allowed only with a dev, demo or test profile; production uses kms");
      }
      return new PassphraseKeyProvider(vault.masterKeys(), vault.currentKeyId());
    }
    if (kms == null) {
      throw new IllegalStateException(
          "fraudshield.vault.key-provider=kms needs a KmsClient bean for the deployment's key"
              + " service; none is bound");
    }
    java.util.Set<String> readable = new java.util.HashSet<>(vault.readableKeyIds());
    readable.add(vault.currentKeyId());
    return new KmsKeyProvider(kms, vault.currentKeyId(), readable);
  }

  /** Profiles in which the vault's keys may come from configuration. */
  static final String CONFIGURED_KEY_PROFILES = "dev | demo | test";

  /**
   * The PII vault's contact directory (D-20, ADR 0069).
   *
   * <p>Its own data source: a separate server, a separate role and separate credentials from the
   * main database, so a compromise of one is not a compromise of the other. Without this bean the
   * SMS channel does not start, and customer intents stay on their topic.
   *
   * @param properties configuration
   * @param keys the vault's key provider
   * @return the directory, or nothing when no vault is configured
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "fraudshield.vault",
      name = "url")
  VaultContacts vaultContacts(FraudShieldProperties properties, KeyProvider keys) {
    // The URL is not logged: a JDBC URL can carry a password, and this line would publish it.
    LOG.info("the PII vault is configured; customer SMS can be sent");
    return new VaultContacts(vaultDataSource(properties.vault()), keys);
  }

  /**
   * The tokenisation map (D-20, ADR 0069 point 9), for the institution onboarding that enrols
   * accounts (M7).
   *
   * @param properties configuration
   * @param keys the vault's key provider
   * @param kms the key service binding, which unwraps the index key under {@code kms}
   * @return the map
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "fraudshield.vault",
      name = "url")
  AccountTokens accountTokens(
      FraudShieldProperties properties, KeyProvider keys, ObjectProvider<KmsClient> kms) {
    FraudShieldProperties.Vault vault = properties.vault();
    byte[] indexKey;
    try {
      indexKey = java.util.Base64.getDecoder().decode(vault.indexKey());
    } catch (IllegalArgumentException notBase64) {
      throw new IllegalArgumentException("fraudshield.vault.index-key is not base64", notBase64);
    }
    if (FraudShieldProperties.Vault.KMS.equals(vault.keyProvider())) {
      indexKey =
          kms.getObject()
              .decrypt(
                  vault.indexKeyId(), indexKey, java.util.Map.of("purpose", INDEX_KEY_PURPOSE));
    }
    try {
      return new AccountTokens(vaultDataSource(vault), keys, indexKey);
    } finally {
      java.util.Arrays.fill(indexKey, (byte) 0);
    }
  }

  /** The key-service context the index key is wrapped under. */
  static final String INDEX_KEY_PURPOSE = "fraudshield-vault-index-key";

  private static DataSource vaultDataSource(FraudShieldProperties.Vault vault) {
    org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
    source.setUrl(vault.url());
    source.setUser(vault.username());
    source.setPassword(vault.password());
    return source;
  }

  @Bean(destroyMethod = "close")
  Channels notificationChannels(
      FraudShieldProperties properties,
      DataSource dataSource,
      Clock clock,
      InstitutionMessaging institutions,
      VerificationService verifications,
      ObjectProvider<WebhookEndpoints> endpoints,
      ObjectProvider<WebhookTransport> transport,
      ObjectProvider<ContactDirectory> contacts,
      ObjectProvider<SmsGateway> gateway) {
    Channels channels = new Channels();
    WebhookEndpoints registry = endpoints.getIfAvailable();
    if (registry == null) {
      LOG.warn(
          "no WebhookEndpoints bean (the API-key module provides it): decision.final"
              + " webhooks are not sent; states stay on fs.decisions.final and GET /decisions");
    } else {
      WebhookDispatcher dispatcher =
          new WebhookDispatcher(
              dataSource,
              registry,
              transport.getIfAvailable(
                  () -> new HttpWebhookTransport(HttpWebhookTransport.Destinations.PUBLIC_HTTPS)),
              new RetryPolicy(),
              clock);
      channels.dispatcher = dispatcher;
      channels.running.add(
          new EnvelopeConsumer(
              properties.kafkaBootstrapServers(),
              Map.of(),
              "fs.decisions.final",
              "webhook-dispatcher",
              envelope -> dispatcher.enqueue(envelope.institutionId(), envelope.payload())));
      ScheduledExecutorService delivery =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().name("webhook-delivery").daemon(true).factory());
      delivery.scheduleWithFixedDelay(
          () -> {
            try {
              dispatcher.deliverDue(100);
            } catch (SQLException | RuntimeException e) {
              LOG.warn("webhook delivery failed; retrying", e);
            }
          },
          100,
          100,
          TimeUnit.MILLISECONDS);
      channels.running.add(delivery::shutdownNow);
    }
    ContactDirectory directory = contacts.getIfAvailable();
    if (directory == null) {
      LOG.warn(
          "no ContactDirectory bean (the PII vault adapter): customer SMS intents are not"
              + " sent; they stay on fs.notifications.customer");
    } else {
      SmsGateway provider =
          gateway.getIfAvailable(
              () -> {
                FraudShieldProperties.Sms sms = properties.sms();
                if (sms == null || sms.baseUrl() == null || sms.apiKey() == null) {
                  throw new IllegalStateException(
                      "fraudshield.sms.base-url and api-key are required to send customer SMS");
                }
                return new AfricasTalkingGateway(sms.baseUrl(), sms.username(), sms.apiKey());
              });
      CustomerSmsSender sender =
          new CustomerSmsSender(
              dataSource,
              directory,
              institutions,
              verifications,
              provider,
              new SmsCatalogue(),
              clock);
      channels.sender = sender;
      channels.running.add(
          new EnvelopeConsumer(
              properties.kafkaBootstrapServers(),
              Map.of(),
              "fs.notifications.customer",
              "notification-service",
              envelope ->
                  sender.send(
                      envelope.institutionId(), envelope.payload(), envelope.transactionId())));
    }
    return channels;
  }
}
