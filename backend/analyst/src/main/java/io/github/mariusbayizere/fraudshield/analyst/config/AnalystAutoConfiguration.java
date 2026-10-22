package io.github.mariusbayizere.fraudshield.analyst.config;

import io.github.mariusbayizere.fraudshield.analyst.accounts.AccountController;
import io.github.mariusbayizere.fraudshield.analyst.accounts.AccountQueries;
import io.github.mariusbayizere.fraudshield.analyst.accounts.BehaviourProfiles;
import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertController;
import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.analyst.decisions.AlertDecisions;
import io.github.mariusbayizere.fraudshield.analyst.decisions.DecisionController;
import io.github.mariusbayizere.fraudshield.analyst.decisions.DecisionWorkerTasks;
import io.github.mariusbayizere.fraudshield.analyst.decisions.DecisionWorkers;
import io.github.mariusbayizere.fraudshield.analyst.scoring.ScoringResultController;
import io.github.mariusbayizere.fraudshield.analyst.support.CountryPacks;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the analyst API (M8B) on top of staff identity, as M7's administration is: P1's reads, and
 * P2's decisions where the application has the decision-owner contract's owner and recorder.
 */
@AutoConfiguration(
    afterName = "io.github.mariusbayizere.fraudshield.auth.config.AuthAutoConfiguration")
@ConditionalOnBean(SessionService.class)
@Import({AlertController.class, ScoringResultController.class, AccountController.class})
public class AnalystAutoConfiguration {

  @Bean
  AlertQueries alertQueries(JdbcTemplate jdbc) {
    return new AlertQueries(jdbc);
  }

  @Bean
  AccountQueries accountQueries(JdbcTemplate jdbc) {
    return new AccountQueries(jdbc, new BehaviourProfiles(CountryPacks.load()));
  }

  /**
   * P2's decisions (decision-owner contract §9), wired only where the assembled application
   * provides the transaction's owner and recorder.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(RequestRecorder.class)
  @Import(DecisionController.class)
  static class Decisions {

    @Bean
    AlertDecisions alertDecisions(
        TenantTransactions tenants,
        JdbcTemplate jdbc,
        DataSource dataSource,
        DecisionOwner owner,
        RequestRecorder recorder,
        AuditLog audit,
        AlertQueries alerts,
        ObjectProvider<HoldSchedulePort> holds,
        ObjectProvider<Clock> clock) {
      return new AlertDecisions(
          tenants,
          jdbc,
          dataSource,
          owner,
          recorder,
          audit,
          alerts,
          holds.getIfAvailable(),
          AlertDecisions.DEFAULT_ESCALATION_WINDOW,
          clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    DecisionWorkers decisionWorkers(
        TenantTransactions tenants,
        JdbcTemplate jdbc,
        DataSource dataSource,
        DecisionOwner owner,
        RequestRecorder recorder,
        AuditLog audit,
        ObjectProvider<CircuitBreakerPort> breakers,
        ObjectProvider<HoldSchedulePort> holds,
        ObjectProvider<Clock> clock) {
      return new DecisionWorkers(
          tenants,
          jdbc,
          dataSource,
          owner,
          recorder,
          audit,
          breakers.getIfAvailable(),
          holds.getIfAvailable(),
          clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    DecisionWorkerTasks decisionWorkerTasks(DecisionWorkers workers) {
      return new DecisionWorkerTasks(workers);
    }
  }
}
