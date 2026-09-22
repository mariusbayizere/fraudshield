package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessages;
import io.github.mariusbayizere.fraudshield.decision.adapter.grpc.GrpcScorer;
import io.github.mariusbayizere.fraudshield.decision.adapter.grpc.ScorerChannels;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcAccountStatus;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcConfiguration;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcFreezes;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcFxRates;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcOverdueHolds;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.PostgresSink;
import io.github.mariusbayizere.fraudshield.decision.adapter.kafka.KafkaSink;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisAccountStatus;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisCircuitBreakers;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisFreezes;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisHoldSchedule;
import io.github.mariusbayizere.fraudshield.decision.adapter.resilience.DegradedMode;
import io.github.mariusbayizere.fraudshield.decision.adapter.resilience.ResilientPorts;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.DurableSpool;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolDrainer;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolingEventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.CircuitBreakerMonitor;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionTransitionService;
import io.github.mariusbayizere.fraudshield.decision.application.HoldTimeoutService;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.FxRatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
import io.github.mariusbayizere.fraudshield.decision.domain.FallbackRules;
import io.github.mariusbayizere.fraudshield.ingest.application.BatchJobs;
import io.github.mariusbayizere.fraudshield.ingest.application.IngestService;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.ingest.idempotency.IdempotencyStore;
import io.github.mariusbayizere.fraudshield.ingest.idempotency.JdbcIdempotency;
import io.github.mariusbayizere.fraudshield.ingest.idempotency.RedisIdempotency;
import io.github.mariusbayizere.fraudshield.ingest.idempotency.ResilientIdempotency;
import io.github.mariusbayizere.fraudshield.ingest.web.ApiKeyFilter;
import io.github.mariusbayizere.fraudshield.notify.sms.CustomerSmsPolicy;
import io.github.mariusbayizere.fraudshield.notify.verification.UnblockReconciler;
import io.github.mariusbayizere.fraudshield.notify.verification.VerificationService;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * The composition root of the decision path: every port bound to its adapter, the Redis adapters
 * wrapped with their C.4 fallbacks, and the durable spool drained to Kafka and PostgreSQL. No test
 * double is wired here; the beans other modules must supply (the API-key authenticator above all)
 * are required, so a missing one stops the application at start rather than failing later.
 */
@Configuration(proxyBeanMethods = false)
public class DecisionWiring {

  /** Spool consumer that publishes to Kafka. */
  static final String KAFKA_CONSUMER = "kafka";

  /** Spool consumer that writes PostgreSQL. */
  static final String POSTGRES_CONSUMER = "postgres";

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean(destroyMethod = "shutdown")
  RedisClient redisClient(FraudShieldProperties properties) {
    RedisClient client = RedisClient.create(properties.redisUri());
    client.setOptions(
        ClientOptions.builder()
            .timeoutOptions(TimeoutOptions.enabled(properties.redisTimeout()))
            .build());
    return client;
  }

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, String> redis(RedisClient client) {
    return client.connect();
  }

  @Bean
  DegradedMode degradedMode(MeterRegistry registry) {
    DegradedMode mode = new DegradedMode();
    Gauge.builder("fs_degraded_mode", mode, m -> m.degraded() ? 1 : 0)
        .description("1 while Redis is down and the fallbacks decide (C.4)")
        .register(registry);
    return mode;
  }

  @Bean(destroyMethod = "close")
  DurableSpool spool(FraudShieldProperties properties, MeterRegistry registry) {
    DurableSpool spool =
        new DurableSpool(
            Path.of(properties.spoolDirectory()),
            new DurableSpool.Settings(properties.spoolMaxBytes(), 64L << 20, 10_000),
            Set.of(KAFKA_CONSUMER, POSTGRES_CONSUMER));
    Gauge.builder("fs_spool_depth", spool, DurableSpool::depthBytes)
        .baseUnit("bytes")
        .description("Durable but not yet delivered to every consumer (D-15)")
        .register(registry);
    return spool;
  }

  @Bean(destroyMethod = "close")
  KafkaSink kafkaSink(FraudShieldProperties properties) {
    return new KafkaSink(
        properties.kafkaBootstrapServers(),
        Map.of(),
        new KafkaMessages("fraudshield-api", properties.auditWriterPartition()),
        30_000);
  }

  @Bean(destroyMethod = "close")
  SpoolDrainer kafkaDrainer(DurableSpool spool, KafkaSink sink) {
    return new SpoolDrainer(
        spool, KAFKA_CONSUMER, sink, 500, Duration.ofMillis(2), Duration.ofSeconds(5));
  }

  @Bean(destroyMethod = "close")
  SpoolDrainer postgresDrainer(
      DurableSpool spool, DataSource dataSource, FraudShieldProperties properties) {
    return new SpoolDrainer(
        spool,
        POSTGRES_CONSUMER,
        new PostgresSink(dataSource, Path.of(properties.spoolDirectory()).resolve("dead-letters")),
        200,
        Duration.ofMillis(5),
        Duration.ofSeconds(5));
  }

  @Bean
  EventRecorder eventRecorder(DurableSpool spool) {
    return new SpoolingEventRecorder(spool, Duration.ofSeconds(1));
  }

  @Bean(destroyMethod = "close")
  GrpcScorer scorer(FraudShieldProperties properties, ResourceLoader resources) throws IOException {
    FraudShieldProperties.Scorer s = properties.scorer();
    ScorerChannels.Settings settings =
        s.plaintext()
            ? new ScorerChannels.Settings(s.target(), true, null, null, null)
            : new ScorerChannels.Settings(
                s.target(),
                false,
                resources.getResource(s.trustCertificate()).getContentAsByteArray(),
                resources.getResource(s.clientCertificate()).getContentAsByteArray(),
                resources.getResource(s.clientKey()).getContentAsByteArray());
    GrpcScorer scorer = new GrpcScorer(ScorerChannels.open(settings), s.deadline());
    scorer.warmUp(Duration.ofSeconds(5));
    return scorer;
  }

  @Bean
  JdbcConfiguration configuration(DataSource dataSource, Clock clock) {
    return new JdbcConfiguration(dataSource, clock);
  }

  @Bean
  JdbcDecisionStates durableStates(DataSource dataSource) {
    return new JdbcDecisionStates(dataSource);
  }

  @Bean
  DecisionStatePort decisionStates(
      StatefulRedisConnection<String, String> redis,
      JdbcDecisionStates durable,
      DegradedMode mode,
      FraudShieldProperties properties) {
    return ResilientPorts.decisionStates(
        new RedisDecisionStates(redis, durable, properties.redisTimeout()), durable, mode);
  }

  @Bean
  HoldSchedulePort holds(
      StatefulRedisConnection<String, String> redis,
      DegradedMode mode,
      FraudShieldProperties properties) {
    return ResilientPorts.holds(new RedisHoldSchedule(redis, properties.redisTimeout()), mode);
  }

  @Bean
  CircuitBreakerPort breakers(
      StatefulRedisConnection<String, String> redis,
      DegradedMode mode,
      FraudShieldProperties properties) {
    return ResilientPorts.breakers(
        new RedisCircuitBreakers(redis, properties.redisTimeout()), mode);
  }

  @Bean
  DecisionMetrics decisionMetrics(MeterRegistry registry) {
    return new MicrometerDecisionMetrics(registry);
  }

  @Bean
  DecisionService decisionService(
      JdbcConfiguration configuration,
      StatefulRedisConnection<String, String> redis,
      DataSource dataSource,
      ScoringPort scorer,
      CircuitBreakerPort breakers,
      HoldSchedulePort holds,
      DecisionStatePort states,
      EventRecorder recorder,
      DecisionMetrics metrics,
      DegradedMode mode,
      FraudShieldProperties properties,
      Clock clock,
      ExecutorService afterResponse) {
    JdbcAccountStatus durableStatus = new JdbcAccountStatus(dataSource);
    return new DecisionService(
        configuration,
        ResilientPorts.accountStatus(
            new RedisAccountStatus(
                redis, durableStatus, properties.redisTimeout(), Duration.ofMillis(50)),
            durableStatus,
            mode),
        scorer,
        ResilientPorts.freezes(
            new RedisFreezes(redis, properties.redisTimeout()), new JdbcFreezes(dataSource), mode),
        breakers,
        holds,
        states,
        recorder,
        new CustomerSmsPolicy(properties.defaultLocale()),
        metrics,
        new DecisionSettings(
            properties.reviewWindow(), properties.anomalyReviewThreshold(), FallbackRules.DEFAULTS),
        clock,
        afterResponse);
  }

  @Bean
  HoldTimeoutService holdTimeouts(
      HoldSchedulePort holds,
      DecisionStatePort states,
      EventRecorder recorder,
      DecisionMetrics metrics,
      Clock clock,
      FraudShieldProperties properties) {
    return new HoldTimeoutService(holds, states, recorder, metrics, clock, properties.instanceId());
  }

  @Bean
  UnblockReconciler unblockReconciler(
      DataSource dataSource,
      DecisionTransitionService transitions,
      HoldSchedulePort holds,
      Clock clock,
      FraudShieldProperties properties) {
    return new UnblockReconciler(dataSource, transitions, holds, properties.instanceId(), clock);
  }

  @Bean
  JdbcOverdueHolds overdueHolds(DataSource dataSource) {
    return new JdbcOverdueHolds(dataSource);
  }

  @Bean
  DecisionTransitionService transitions(
      HoldSchedulePort holds, DecisionStatePort states, EventRecorder recorder, Clock clock) {
    return new DecisionTransitionService(holds, states, recorder, clock);
  }

  @Bean
  CircuitBreakerMonitor breakerMonitor(
      CircuitBreakerPort breakers,
      JdbcConfiguration configuration,
      EventRecorder recorder,
      Clock clock) {
    return new CircuitBreakerMonitor(breakers, configuration, recorder, clock);
  }

  @Bean
  FxRatePort fxRates(DataSource dataSource, Clock clock) {
    return new JdbcFxRates(dataSource, clock);
  }

  @Bean
  IdempotencyStore idempotency(
      StatefulRedisConnection<String, String> redis,
      DataSource dataSource,
      DegradedMode mode,
      FraudShieldProperties properties,
      Clock clock,
      MeterRegistry registry) {
    var unverified =
        Counter.builder("fs_idempotency_unverified_claims_total")
            .description("Claims decided without the PostgreSQL verification ADR 0067 asks for")
            .register(registry);
    return new ResilientIdempotency(
        new RedisIdempotency(
            redis, properties.idempotencyLease(), properties.redisTimeout(), clock),
        new JdbcIdempotency(dataSource),
        mode,
        clock,
        unverified::increment);
  }

  @Bean
  IngestService ingestService(
      DecisionService decisions,
      IdempotencyStore idempotency,
      FxRatePort rates,
      EventRecorder recorder,
      Clock clock,
      FraudShieldProperties properties,
      DecisionMetrics metrics) {
    return new IngestService(
        decisions, idempotency, rates, recorder, clock, properties.duplicateWait(), metrics);
  }

  /** Work done after the response (C.2): MCC circuit-breaker counts. */
  @Bean(destroyMethod = "close")
  ExecutorService afterResponse() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService batchExecutor(FraudShieldProperties properties) {
    return Executors.newFixedThreadPool(
        properties.batchThreads(), Thread.ofPlatform().name("batch-", 0).daemon(true).factory());
  }

  @Bean
  BatchJobs batchJobs(
      DataSource dataSource,
      IngestService ingest,
      IdempotencyStore idempotency,
      ExecutorService batchExecutor,
      Clock clock) {
    return new BatchJobs(dataSource, ingest, idempotency, batchExecutor, clock);
  }

  @Bean
  VerificationService verifications(
      DataSource dataSource, DecisionTransitionService transitions, Clock clock) {
    return new VerificationService(dataSource, transitions, clock);
  }

  @Bean
  FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyAuthenticator authenticator) {
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(new ApiKeyFilter(authenticator));
    registration.setOrder(0);
    return registration;
  }
}
