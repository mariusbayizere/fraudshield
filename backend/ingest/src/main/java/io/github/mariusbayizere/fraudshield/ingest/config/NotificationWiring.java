package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.notify.kafka.EnvelopeConsumer;
import io.github.mariusbayizere.fraudshield.notify.sms.AfricasTalkingGateway;
import io.github.mariusbayizere.fraudshield.notify.sms.ContactDirectory;
import io.github.mariusbayizere.fraudshield.notify.sms.CustomerSmsSender;
import io.github.mariusbayizere.fraudshield.notify.sms.InstitutionMessaging;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsCatalogue;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsGateway;
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
              dispatcher::enqueue));
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
              sender::send));
    }
    return channels;
  }
}
