package io.github.mariusbayizere.fraudshield.analyst.decisions;

import io.github.mariusbayizere.fraudshield.analyst.config.AnalystAutoConfiguration;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerTransactions;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * What the assembled application's decision wiring provides, for the analyst module's own tests:
 * the transaction's owner and its runner, and a recorder whose spool keeps what it is given, so
 * that a test can see what an owner request recorded.
 */
@AutoConfiguration(before = AnalystAutoConfiguration.class)
public class OwnerTestAutoConfiguration {

  /** The spool of the analyst tests: it keeps every recorded event. */
  public static final class RecordingSpool implements EventRecorder {
    /** What was recorded, in order. */
    public final List<DecisionEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(List<DecisionEvent> batch) {
      events.addAll(batch);
    }
  }

  @Bean
  RecordingSpool recordingSpool() {
    return new RecordingSpool();
  }

  @Bean
  OwnerTransactions ownerTransactions(DataSource dataSource) {
    return new OwnerTransactions(dataSource, 3);
  }

  @Bean
  DecisionOwner decisionOwner() {
    return new DecisionOwner();
  }

  @Bean
  RequestRecorder requestRecorder(
      DataSource dataSource, OwnerTransactions transactions, RecordingSpool spool) {
    DecisionStatePort noCache =
        new DecisionStatePort() {
          @Override
          public boolean save(DecisionState state) {
            return true;
          }

          @Override
          public Optional<DecisionState> latest(UUID institutionId, UUID transactionId) {
            return Optional.empty();
          }
        };
    return new RequestRecorder(dataSource, transactions, spool, noCache);
  }
}
