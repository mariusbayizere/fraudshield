package io.github.mariusbayizere.fraudshield.analyst.performance;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The header statistics, the caller's own performance and the team table (FR-04-11, UX-DASH-01, ADR
 * 0117). "Today" is the institution's day in its own time zone, and every answer says which day
 * that was.
 */
@RestController
public class PerformanceController {

  private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");
  private static final long RETRY_AFTER_SECONDS = 5;

  private final TenantTransactions tenants;
  private final PerformanceQueries queries;
  private final DashboardFigures figures;
  private final AuditLog audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param tenants tenant transactions
   * @param queries the figures
   * @param figures the header figures
   * @param audit the audit log, for the CSV export
   * @param jdbc JDBC template, for the audit's actor
   * @param clock the clock
   */
  public PerformanceController(
      TenantTransactions tenants,
      PerformanceQueries queries,
      DashboardFigures figures,
      AuditLog audit,
      JdbcTemplate jdbc,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants);
    this.queries = Objects.requireNonNull(queries);
    this.figures = Objects.requireNonNull(figures);
    this.audit = Objects.requireNonNull(audit);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * The header statistics (SRS 5.4, ADR 0117 point 4).
   *
   * @param jwt caller
   * @return the contract's {@code DashboardStats}
   */
  @ContractOperation("getDashboardStats")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/api/v1/dashboard/stats")
  public Map<String, Object> dashboard(@AuthenticationPrincipal Jwt jwt) {
    return figures.stats(AccessTokens.claims(jwt).institutionId());
  }

  /**
   * The caller's own figures and their 30-day trend (FR-04-11, ADR 0117 point 6).
   *
   * @param jwt caller
   * @return the contract's {@code AnalystPerformance}
   */
  @ContractOperation("getMyPerformance")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/api/v1/me/performance")
  public Map<String, Object> mine(@AuthenticationPrincipal Jwt jwt) {
    StaffClaims claims = AccessTokens.claims(jwt);
    final Instant now = clock.instant();
    return tenants.inTenant(
        claims.institutionId(),
        () -> {
          InstitutionDay today = InstitutionDay.of(queries.zone(), now);
          Map<String, Object> mine = queries.mine(claims.userId(), today);
          day(mine, today);
          return mine;
        });
  }

  /**
   * Every analyst's figures over a range, as JSON or, with {@code Accept: text/csv}, as an audited
   * CSV export (UX-ROLE-SN-04, ADR 0117 points 7 and 8).
   *
   * @param jwt caller
   * @param from the first institution day, or null
   * @param to the last institution day, or null
   * @param accept the Accept header, or null
   * @param request the request, for the audit's context
   * @return the contract's {@code TeamPerformance}, or its CSV
   */
  @ContractOperation("getTeamPerformance")
  @PreAuthorize("hasAnyRole('SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping(
      path = "/api/v1/team/performance",
      produces = {MediaType.APPLICATION_JSON_VALUE, "text/csv"})
  public ResponseEntity<?> team(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    final Instant now = clock.instant();
    // The filter rule: a SENIOR_ANALYST sees ANALYST and SENIOR_ANALYST rows only.
    final boolean withRiskOfficers = claims.role() == StaffRole.RISK_OFFICER;
    Map<String, Object> team =
        tenants.inTenant(
            claims.institutionId(),
            () -> {
              ZoneId zone = queries.zone();
              InstitutionDay today = InstitutionDay.of(zone, now);
              Figures.Range range = Figures.Range.of(from, to, today.date());
              List<Map<String, Object>> rows = queries.team(range, zone, withRiskOfficers);
              if (rows.size() > Figures.MAX_ROWS) {
                throw tooLarge();
              }
              InstitutionDay first = new InstitutionDay(zone, range.from());
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("from", range.from().toString());
              body.put("to", range.to().toString());
              day(body, first);
              body.put("agreement_from", range.to().minusDays(Figures.WINDOW_DAYS - 1L).toString());
              body.put("generated_at", Views.timestamp(now.truncatedTo(ChronoUnit.MILLIS)));
              body.put("analysts", rows);
              return body;
            });
    if (!wantsCsv(accept)) {
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(team);
    }
    byte[] csv = TeamCsv.write(team);
    if (csv.length > TeamCsv.MAX_BYTES) {
      throw tooLarge();
    }
    exported(claims, team, request);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, TeamCsv.MEDIA_TYPE)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("team-performance_" + team.get("from") + "_" + team.get("to") + ".csv")
                .build()
                .toString())
        .body(csv);
  }

  /**
   * The export's audit event, written and committed before the first byte of the file is sent; if
   * it cannot be, the answer is 503 and no file is sent (point 8).
   */
  private void exported(StaffClaims claims, Map<String, Object> team, HttpServletRequest request) {
    @SuppressWarnings("unchecked")
    final int rows = ((List<Object>) team.get("analysts")).size();
    try {
      tenants.runInTenant(
          claims.institutionId(),
          () -> {
            Map<String, Object> user =
                jdbc.queryForMap(
                    "SELECT first_name, last_name FROM users WHERE id = ?", claims.userId());
            final AuditActor actor =
                new AuditActor(
                    claims.userId(),
                    (String) user.get("first_name"),
                    (String) user.get("last_name"),
                    claims.role());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("from", team.get("from"));
            after.put("to", team.get("to"));
            after.put("utc_offset", team.get("utc_offset"));
            after.put("time_zone", team.get("time_zone"));
            after.put("rows", rows);
            audit.record(
                AuditEvent.of(
                        claims.institutionId(),
                        AuditEventType.USER_ADMIN,
                        "TEAM_PERFORMANCE_EXPORTED")
                    .entity("team_performance", team.get("from") + "/" + team.get("to"))
                    .actor(actor)
                    .after(after)
                    .context(Requests.context(request))
                    .at(clock.instant()));
          });
    } catch (DataAccessException | TransactionException failed) {
      throw ProblemException.serviceUnavailable(RETRY_AFTER_SECONDS);
    }
  }

  private static void day(Map<String, Object> figures, InstitutionDay day) {
    DashboardFigures.day(figures, day);
  }

  private static ProblemException tooLarge() {
    return ProblemException.of(
        "result-too-large",
        422,
        "Result too large",
        "The answer would have more than "
            + Figures.MAX_ROWS
            + " rows or "
            + TeamCsv.MAX_BYTES
            + " bytes; it is never truncated");
  }

  /**
   * Whether the client prefers CSV: the most preferred acceptable type that is CSV ({@code
   * text/csv} or {@code text/*}) before any that is JSON; a wildcard or no header means JSON.
   *
   * @param accept the Accept header, or null
   * @return true for CSV
   */
  static boolean wantsCsv(String accept) {
    if (accept == null || accept.isBlank()) {
      return false;
    }
    List<MediaType> types = new ArrayList<>(MediaType.parseMediaTypes(accept));
    types.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
    for (MediaType type : types) {
      if (type.getQualityValue() <= 0) {
        continue;
      }
      if (type.isWildcardType()) {
        return false;
      }
      if (type.includes(TEXT_CSV)) {
        return true;
      }
      if (type.includes(MediaType.APPLICATION_JSON)) {
        return false;
      }
    }
    return false;
  }
}
