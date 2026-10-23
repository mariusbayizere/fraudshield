package io.github.mariusbayizere.fraudshield.audit.config;

import io.github.mariusbayizere.fraudshield.audit.anchor.AuditAnchorService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the anchoring job once a day for the previous UTC day (D-32). */
public class AuditAnchorScheduler {

  private final AuditAnchorService service;
  private final Clock clock;

  /**
   * Creates the scheduler.
   *
   * @param service anchoring service
   * @param clock clock
   */
  public AuditAnchorScheduler(AuditAnchorService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  /** Anchors the previous day. */
  @Scheduled(cron = "${fraudshield.audit.anchor.cron:0 10 0 * * *}", zone = "UTC")
  public void anchorPreviousDay() {
    service.anchorAll(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1));
  }
}
