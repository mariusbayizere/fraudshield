package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.analyst.performance.DashboardFigures;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Wires the real-time channel (ADR 0115): the change publisher where Kafka is configured, and the
 * STOMP endpoint at {@code /api/v1/realtime}, exact path only. Without Kafka the endpoint still
 * answers its handshake rules, and refuses upgrades with 503 because no consumer is positioned.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(RealtimeSettings.class)
@ConditionalOnBean({SessionStateCache.class, JwtDecoder.class})
public class RealtimeConfiguration implements WebSocketConfigurer {

  /** The endpoint's exact path. */
  public static final String PATH = "/api/v1/realtime";

  private final ObjectProvider<StompEndpoint> endpoint;
  private final ObjectProvider<HandshakeGate> gate;

  RealtimeConfiguration(
      ObjectProvider<StompEndpoint> endpoint, ObjectProvider<HandshakeGate> gate) {
    this.endpoint = endpoint;
    this.gate = gate;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(endpoint.getObject(), PATH)
        .setHandshakeHandler(new HandshakeGate.Handshakes())
        .addInterceptors(gate.getObject())
        // The gate checks Origin itself, so its refusal is a problem document (point 2).
        .setAllowedOriginPatterns("*");
  }

  @Bean
  InstanceBudget realtimeBudget(RealtimeSettings settings) {
    settings.checkFits(Runtime.getRuntime().maxMemory());
    return new InstanceBudget(settings.instanceBudgetBytes());
  }

  @Bean
  SessionRegistry realtimeSessions(
      RealtimeSettings settings, InstanceBudget budget, ObjectProvider<MeterRegistry> meters) {
    SessionRegistry registry = new SessionRegistry(settings, budget, System::nanoTime);
    meters.ifAvailable(
        m -> {
          Gauge.builder("fs_realtime_sessions", registry, SessionRegistry::authenticated)
              .description("Authenticated real-time sessions on this instance")
              .register(m);
          Gauge.builder("fs_realtime_queued_bytes", budget, InstanceBudget::queued)
              .description("Bytes queued across sessions, counted per queue (ADR 0115 point 8)")
              .register(m);
        });
    return registry;
  }

  @Bean(destroyMethod = "close")
  StaffNames realtimeNames(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      RealtimeSettings settings,
      ObjectProvider<MeterRegistry> meters) {
    StaffNames names = new StaffNames(tenants, jdbc, settings, System::nanoTime);
    meters.ifAvailable(
        m -> {
          FunctionCounter.builder("fs_realtime_unresolved_total", names, StaffNames::unresolved)
              .description("Events dropped because a staff name could not be resolved")
              .register(m);
          FunctionCounter.builder("fs_realtime_name_cache_miss_total", names, StaffNames::misses)
              .description("Staff names the fan-out had to resolve (ADR 0115 point 6.2)")
              .register(m);
        });
    return names;
  }

  @Bean
  FiguresPusher realtimeFigures(
      SessionRegistry registry,
      ObjectProvider<DashboardFigures> figures,
      ObjectProvider<Clock> clock) {
    DashboardFigures source = figures.getIfAvailable();
    return new FiguresPusher(
        registry,
        institution -> {
          if (source == null) {
            throw new IllegalStateException("no figures in this application");
          }
          return source.stats(institution);
        },
        System::nanoTime,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(FanOut.class)
  FanOut realtimeFanOut(
      @Value("${fraudshield.kafka-bootstrap-servers:}") String bootstrapServers,
      StaffNames names,
      SessionRegistry registry,
      RealtimeSettings settings,
      FiguresPusher figures,
      ObjectProvider<MeterRegistry> meters) {
    MeterRegistry m = meters.getIfAvailable();
    Set<TopicPartition> gauged = ConcurrentHashMap.newKeySet();
    FanOut[] self = new FanOut[1];
    FanOut fanOut =
        new FanOut(
            bootstrapServers.isBlank() ? null : FanOut.kafka(bootstrapServers),
            new AlertFrames(),
            names,
            registry,
            figures::alertSeen,
            settings,
            System::nanoTime,
            p -> {
              if (m != null && gauged.add(p)) {
                Gauge.builder("fs_realtime_consumer_lag", self[0], f -> f.lag().getOrDefault(p, 0L))
                    .description("End offset minus position, per partition (ADR 0115 point 5)")
                    .tags(Tags.of("topic", p.topic(), "partition", Integer.toString(p.partition())))
                    .register(m);
              }
            });
    self[0] = fanOut;
    if (m != null) {
      FunctionCounter.builder("fs_realtime_invalid_total", fanOut, FanOut::invalid)
          .description("Fan-out records dropped as invalid (ADR 0115 point 5)")
          .register(m);
      FunctionCounter.builder(
              "fs_realtime_partition_refresh_failed_total", fanOut, FanOut::refreshMisses)
          .description("Partition refreshes that failed and kept the old assignment (point 5)")
          .register(m);
      FunctionCounter.builder("fs_realtime_lag_unreadable_total", fanOut, FanOut::lagMisses)
          .description("Lag readings that failed; the last value is kept (point 5)")
          .register(m);
      FunctionCounter.builder("fs_realtime_overflow_total", fanOut, FanOut::overflows)
          .description("Sessions closed with realtime-overflow by the fan-out (ADR 0115 point 8)")
          .register(m);
    }
    return fanOut;
  }

  @Bean(destroyMethod = "close")
  SessionGuard realtimeGuard(
      SessionRegistry registry,
      SessionStateCache sessions,
      SafeRedis redis,
      RealtimeSettings settings,
      FanOut fanOut,
      ObjectProvider<Clock> clock) {
    return new SessionGuard(
        registry,
        sessions,
        redis,
        settings,
        fanOut::healthy,
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("fs-realtime-guard").daemon().factory()),
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("fs-realtime-checks").daemon().factory()),
        System::nanoTime,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  StompEndpoint realtimeEndpoint(
      SessionRegistry registry,
      SessionGuard guard,
      JwtDecoder tokens,
      RealtimeSettings settings,
      InstanceBudget budget,
      FiguresPusher figures,
      ObjectProvider<MeterRegistry> meters) {
    MeterRegistry m = meters.getIfAvailable();
    Map<String, Timer> timers = new ConcurrentHashMap<>();
    RealtimeSession.Written written =
        frame -> {
          if (m == null || frame.createdMillis() == 0) {
            return; // the figures are computed here, not read from a Kafka record
          }
          timers
              .computeIfAbsent(
                  frame.destination(),
                  d ->
                      Timer.builder("fs_realtime_push_seconds")
                          .description(
                              "Kafka record CreateTime to socket write (ADR 0115 point 11)")
                          .tag("destination", d)
                          .publishPercentileHistogram()
                          .register(m))
              .record(
                  Duration.ofMillis(
                      Math.max(0, System.currentTimeMillis() - frame.createdMillis())));
        };
    return new StompEndpoint(
        registry, guard, tokens, settings, budget, written, figures, System::nanoTime);
  }

  @Bean
  HandshakeGate realtimeGate(
      SessionRegistry registry, SessionGuard guard, RealtimeSettings settings) {
    return new HandshakeGate(registry, guard, settings.allowedOrigins());
  }

  @Bean
  SmartLifecycle realtimeLifecycle(SessionGuard guard, FanOut fanOut, FiguresPusher figures) {
    return new SmartLifecycle() {
      private final ScheduledExecutorService ticks =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().name("fs-realtime-figures").daemon().factory());
      private volatile boolean running;

      @Override
      public void start() {
        guard.start();
        fanOut.start();
        ticks.scheduleWithFixedDelay(figures::tick, 1, 1, TimeUnit.SECONDS);
        running = true;
      }

      @Override
      public void stop() {
        guard.shutdown();
        ticks.shutdownNow();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }

  /** The change publisher, where Kafka is configured (ADR 0115 point 6.2). */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty("fraudshield.kafka-bootstrap-servers")
  static class Publishing {

    @Bean(destroyMethod = "close")
    KafkaChangeSender realtimeChangeSender(
        @Value("${fraudshield.kafka-bootstrap-servers}") String bootstrapServers,
        RealtimeSettings settings) {
      return new KafkaChangeSender(bootstrapServers, settings);
    }

    @Bean(destroyMethod = "close")
    ChangePublisher alertChangePublisher(
        TenantTransactions tenants,
        JdbcTemplate jdbc,
        AlertQueries alerts,
        KafkaChangeSender sender,
        RealtimeSettings settings,
        ObjectProvider<Clock> clock,
        ObjectProvider<MeterRegistry> meters) {
      ChangePublisher publisher =
          new ChangePublisher(
              tenants,
              jdbc,
              alerts,
              sender,
              "fraudshield-api",
              settings.handOffQueue(),
              clock.getIfAvailable(Clock::systemUTC));
      meters.ifAvailable(
          m -> {
            FunctionCounter.builder(
                    "fs_realtime_publish_dropped_total", publisher, ChangePublisher::dropped)
                .description("Changes dropped: the hand-off queue was full (ADR 0115 point 6.2)")
                .register(m);
            FunctionCounter.builder(
                    "fs_realtime_publish_failed_total", publisher, ChangePublisher::failed)
                .description("Changes that could not be re-read or sent (ADR 0115 point 6.2)")
                .register(m);
          });
      return publisher;
    }
  }
}
