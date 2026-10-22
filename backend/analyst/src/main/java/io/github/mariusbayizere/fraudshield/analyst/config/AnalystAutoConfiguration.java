package io.github.mariusbayizere.fraudshield.analyst.config;

import io.github.mariusbayizere.fraudshield.analyst.accounts.AccountController;
import io.github.mariusbayizere.fraudshield.analyst.accounts.AccountQueries;
import io.github.mariusbayizere.fraudshield.analyst.accounts.BehaviourProfiles;
import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertController;
import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.analyst.assignments.AlertAssignments;
import io.github.mariusbayizere.fraudshield.analyst.assignments.AssignmentController;
import io.github.mariusbayizere.fraudshield.analyst.decisions.AlertDecisions;
import io.github.mariusbayizere.fraudshield.analyst.decisions.DecisionController;
import io.github.mariusbayizere.fraudshield.analyst.decisions.DecisionWorkerTasks;
import io.github.mariusbayizere.fraudshield.analyst.decisions.DecisionWorkers;
import io.github.mariusbayizere.fraudshield.analyst.realtime.RealtimeConfiguration;
import io.github.mariusbayizere.fraudshield.analyst.performance.PerformanceController;
import io.github.mariusbayizere.fraudshield.analyst.performance.PerformanceQueries;
import io.github.mariusbayizere.fraudshield.analyst.performance.Presence;
import io.github.mariusbayizere.fraudshield.analyst.scoring.ScoringResultController;
import io.github.mariusbayizere.fraudshield.analyst.support.CountryPacks;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.decision.application.port.AlertChangePublisher;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Wires the analyst API (M8B) on top of staff identity, as M7's administration is: P1's reads,
 * alert assignment (ADR 0116), and P2's decisions where the application has the decision-owner
 * contract's owner and recorder.
 */
@AutoConfiguration(
    afterName = "io.github.mariusbayizere.fraudshield.auth.config.AuthAutoConfiguration")
@ConditionalOnBean(SessionService.class)
@Import({
  AlertController.class,
  ScoringResultController.class,
  AccountController.class,
  RealtimeConfiguration.class,
  PerformanceController.class,
  AssignmentController.class
})
public class AnalystAutoConfiguration {

  @Bean
  AlertQueries alertQueries(JdbcTemplate jdbc) {
    return new AlertQueries(jdbc);
  }

  /** Alert assignment (ADR 0116): advisory, and independent of the decision owner. */
  @Bean
  AlertAssignments alertAssignments(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      AuditLog audit,
      AlertQueries alerts,
      ObjectProvider<Clock> clock) {
    return new AlertAssignments(
        tenants, jdbc, audit, alerts, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  AccountQueries accountQueries(JdbcTemplate jdbc) {
    return new AccountQueries(jdbc, new BehaviourProfiles(CountryPacks.load()));
  }

  @Bean
  PerformanceQueries performanceQueries(JdbcTemplate jdbc) {
    return new PerformanceQueries(jdbc);
  }

  /**
   * The presence reader of {@code analysts_online} (ADR 0117 point 4). Redis is optional here: an
   * application without it, such as this module's own tests, answers null for the figure rather
   * than failing to start.
   */
  @Bean
  Presence analystPresence(ObjectProvider<SafeRedis> redis, ObjectProvider<Clock> clock) {
    return new Presence(redis.getIfAvailable(), clock.getIfAvailable(Clock::systemUTC));
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
        ObjectProvider<Clock> clock,
        ObjectProvider<AlertChangePublisher> changes) {
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
          clock.getIfAvailable(Clock::systemUTC),
          changes.getIfAvailable(() -> AlertChangePublisher.NONE));
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
        ObjectProvider<Clock> clock,
        ObjectProvider<MeterRegistry> registry,
        ObjectProvider<AlertChangePublisher> changes) {
      DecisionWorkers workers =
          new DecisionWorkers(
              tenants,
              jdbc,
              dataSource,
              owner,
              recorder,
              audit,
              breakers.getIfAvailable(),
              holds.getIfAvailable(),
              clock.getIfAvailable(Clock::systemUTC),
              changes.getIfAvailable(() -> AlertChangePublisher.NONE));
      registry.ifAvailable(
          meters -> {
            FunctionCounter.builder(
                    "fs_analyst_effects_failed_total", workers, DecisionWorkers::effectFailures)
                .description("Analyst effects recorded FAILED (decision-owner contract D4(e))")
                .register(meters);
            FunctionCounter.builder(
                    "fs_analyst_commit_failures_total", workers, DecisionWorkers::commitFailures)
                .description("Analyst commits that failed and were left for the next run (D3)")
                .register(meters);
          });
      return workers;
    }

    @Bean
    DecisionWorkerTasks decisionWorkerTasks(DecisionWorkers workers) {
      return new DecisionWorkerTasks(workers);
    }
  }
}
