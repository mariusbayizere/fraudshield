package io.github.mariusbayizere.fraudshield.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Review finding 1 (BLOCKER): a refresh racing a revocation of the same account must never leave a
 * live token in a revoked family. A stolen refresh token is rotated in a tight loop while the owner
 * signs out everywhere; afterwards nothing of the account may be usable.
 */
@Tag("D-27")
@Tag("FR-07-09")
class RefreshRaceTest extends AuthIntegrationTest {

  private static final int ROUNDS = 15;

  @Autowired private SessionService sessions;

  @Autowired private JwtDecoder decoder;

  @Test
  void signOutEverywhereRacingRotationLeavesNoLiveToken() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
      Session victim = issueSession(account);
      Session stolen = issueSession(account);
      StaffClaims victimClaims = AccessTokens.claims(decoder.decode(victim.accessToken()));
      AtomicReference<String> lastAccess = new AtomicReference<>(stolen.accessToken());
      CountDownLatch rotating = new CountDownLatch(3);
      try (var pool = Executors.newFixedThreadPool(2)) {
        Future<?> thief =
            pool.submit(
                () -> {
                  String refresh = stolen.refreshToken();
                  for (int i = 0; i < 200; i++) {
                    try {
                      IssuedSession next = sessions.refresh(refresh, RequestContext.SYSTEM);
                      refresh = next.refreshToken();
                      lastAccess.set(next.accessToken());
                      rotating.countDown();
                    } catch (ProblemException refused) {
                      return;
                    }
                  }
                });
        rotating.await();
        sessions.logoutEverywhere(victimClaims, RequestContext.SYSTEM);
        thief.get();
      }
      assertThat(
              query(
                  "SELECT count(*) FROM fraudshield.refresh_tokens WHERE user_id = ?"
                      + " AND revoked_at IS NULL",
                  account.id()))
          .as("round %d: no live refresh token survives", round)
          .isEqualTo(0L);
      assertThat(
              http.get("/api/v1/auth/me", "Authorization", "Bearer " + lastAccess.get()).status())
          .as("round %d: the thief's last access token", round)
          .isEqualTo(401);
    }
  }

  @Test
  void revokedButNeverRotatedTokenIsRejectedWithoutReuseAlarm() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = issueSession(account);
    StaffClaims claims = AccessTokens.claims(decoder.decode(session.accessToken()));
    sessions.logout(claims, RequestContext.SYSTEM);
    assertThat(http.post("/api/v1/auth/refresh", null, session.refreshHeaders()).status())
        .isEqualTo(401);
    assertThat(auditActions(claims.sessionId())).doesNotContain("AUTH/REFRESH_TOKEN_REUSED");
  }
}
