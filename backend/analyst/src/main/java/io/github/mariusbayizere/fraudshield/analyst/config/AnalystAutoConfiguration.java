package io.github.mariusbayizere.fraudshield.analyst.config;

import io.github.mariusbayizere.fraudshield.analyst.accounts.AccountController;
import io.github.mariusbayizere.fraudshield.analyst.accounts.AccountQueries;
import io.github.mariusbayizere.fraudshield.analyst.accounts.BehaviourProfiles;
import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertController;
import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.analyst.scoring.ScoringResultController;
import io.github.mariusbayizere.fraudshield.analyst.support.CountryPacks;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the analyst API's reads (M8B P1) on top of staff identity, as M7's administration is. */
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
}
