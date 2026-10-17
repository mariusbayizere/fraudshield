package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.audit.jdbc.RelayedAuditAppender;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessages;
import io.github.mariusbayizere.fraudshield.notify.kafka.EnvelopeConsumer;
import io.github.mariusbayizere.fraudshield.notify.kafka.MalformedPayloadException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The audit writer's consumer (D-32, {@code contracts/kafka/topics.yaml}: group {@code
 * audit-writer}). The decision engine publishes its audit events to {@code fs.audit.events} from
 * its durable spool rather than writing them in a transaction of its own (ADR 0068 as amended);
 * this consumer appends each to the hash-chained {@code audit_events} through the audit module, so
 * audit search and {@code fraudshield audit verify} cover decisions as well as staff actions.
 *
 * <p>M6's {@link EnvelopeConsumer} carries the delivery rules: offsets committed after the append,
 * a database outage re-read without bound, an event the database refuses as data dead-lettered to
 * {@code fs.audit.events.dlq} for replay. Duplicates are skipped on the envelope's {@code event_id}
 * header, which the producer sets on every record.
 */
@Configuration(proxyBeanMethods = false)
public class AuditRelayWiring {

  /** The consumer group of the audit writer in {@code topics.yaml}. */
  static final String GROUP = "audit-writer";

  /** The record header carrying the envelope's event id ({@code KafkaSink}). */
  static final String EVENT_ID_HEADER = "event_id";

  @Bean
  RelayedAuditAppender relayedAuditAppender(TenantTransactions tenants, JdbcTemplate jdbc) {
    return new RelayedAuditAppender(tenants, jdbc);
  }

  @Bean(destroyMethod = "close")
  EnvelopeConsumer auditWriterConsumer(
      FraudShieldProperties properties,
      RelayedAuditAppender appender,
      MeterRegistry meterRegistry,
      Clock clock) {
    return new EnvelopeConsumer(
        properties.kafkaBootstrapServers(),
        Map.of(),
        KafkaMessages.AUDIT,
        GROUP,
        envelope -> {
          try {
            appender.append(
                UUID.fromString(String.valueOf(envelope.headers().get(EVENT_ID_HEADER))),
                envelope.institutionId(),
                envelope.payload());
          } catch (IllegalArgumentException malformed) {
            // No retry changes a record that is not an audit event: dead-lettered at once.
            throw new MalformedPayloadException(
                "not an audit event: " + malformed.getMessage(), malformed);
          }
        },
        EnvelopeConsumer.ParentWait.DEFAULT,
        EnvelopeConsumer.RetryBound.DEFAULT,
        meterRegistry,
        clock);
  }
}
