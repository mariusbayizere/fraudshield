package io.github.mariusbayizere.fraudshield.admin.config;

import io.github.mariusbayizere.fraudshield.admin.apikeys.ApiKeyAdminController;
import io.github.mariusbayizere.fraudshield.admin.audit.AuditSearchController;
import io.github.mariusbayizere.fraudshield.admin.network.IpAllowlistController;
import io.github.mariusbayizere.fraudshield.admin.support.AdminContext;
import io.github.mariusbayizere.fraudshield.admin.support.IdempotencyStore;
import io.github.mariusbayizere.fraudshield.admin.users.UserAdminController;
import io.github.mariusbayizere.fraudshield.admin.users.UserAdministrationService;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordHasher;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Wires the administration API on top of staff identity. */
@AutoConfiguration(
    afterName = "io.github.mariusbayizere.fraudshield.auth.config.AuthAutoConfiguration")
@ConditionalOnBean(SessionService.class)
@Import({
  UserAdminController.class,
  IpAllowlistController.class,
  ApiKeyAdminController.class,
  AuditSearchController.class
})
public class AdminAutoConfiguration {

  @Bean
  AdminContext adminContext(StaffAccountRepository accounts) {
    return new AdminContext(accounts);
  }

  @Bean
  IdempotencyStore idempotencyStore(SafeRedis redis, Clock clock) {
    return new IdempotencyStore(redis, clock);
  }

  @Bean
  UserAdministrationService userAdministrationService(
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      AdminContext admins,
      PasswordHasher hasher,
      SessionService sessions,
      StaffMailer mailer,
      AuditLog audit,
      Clock clock) {
    return new UserAdministrationService(
        tenants, accounts, admins, hasher, sessions, mailer, audit, clock);
  }
}
