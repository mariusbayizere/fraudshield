package io.github.mariusbayizere.fraudshield.notify.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * D-20: the tokenisation map. An account number becomes one permanent token per institution; the
 * vault can turn the token back into the number; the database holds the number only as ciphertext
 * and a keyed hash.
 */
@Tag("requires-docker")
@Tag("D-20")
@Tag("NFR-SEC-03")
class AccountTokensTest {

  private static final UUID INSTITUTION = UUID.randomUUID();
  private static final String NUMBER = "40012345678901";

  private static TestVault vault;
  private static AccountTokens tokens;
  private static String masterKey;
  private static byte[] indexKey;

  @BeforeAll
  static void start() {
    vault = new TestVault();
    masterKey = TestVault.masterKey();
    indexKey = new byte[32];
    new SecureRandom().nextBytes(indexKey);
    tokens =
        new AccountTokens(
            vault.dataSource("fs_vault"),
            new PassphraseKeyProvider(Map.of("m6", masterKey), "m6"),
            indexKey);
  }

  @AfterAll
  static void stop() {
    vault.close();
  }

  @Test
  void numbersHaveOneTokenHoweverTheyAreWrittenAndTheTokenComesBackAsTheNumber() {
    String token = tokens.tokenise(INSTITUTION, NUMBER);
    assertThat(token).matches("^tok_[A-Za-z0-9]{24}$");
    assertThat(tokens.tokenise(INSTITUTION, "4001 2345-6789.01")).isEqualTo(token);
    assertThat(tokens.tokenise(INSTITUTION, "rw40012345678901"))
        .isEqualTo(tokens.tokenise(INSTITUTION, "RW4001 2345 6789 01"));
    assertThat(tokens.tokenise(UUID.randomUUID(), NUMBER))
        .as("another institution's customer is another account")
        .isNotEqualTo(token);
    assertThat(tokens.detokenise(INSTITUTION, token)).contains(NUMBER);
    assertThat(tokens.detokenise(UUID.randomUUID(), token)).isEmpty();
    assertThat(tokens.detokenise(INSTITUTION, "tok_NeverIssuedAaaaBbbbCc12")).isEmpty();
  }

  @Test
  void concurrentFirstRequestsForOneNumberAgreeOnOneToken() throws Exception {
    String number = "7700" + System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Callable<String>> calls = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        calls.add(() -> tokens.tokenise(INSTITUTION, number));
      }
      Set<String> issued = new HashSet<>();
      for (Future<String> f : pool.invokeAll(calls)) {
        issued.add(f.get());
      }
      assertThat(issued).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            count(
                "SELECT count(*) FROM vault.account_tokens WHERE account_token = '"
                    + tokens.tokenise(INSTITUTION, number)
                    + "'"))
        .isEqualTo(1);
  }

  @Test
  void theDatabaseHoldsNeitherTheNumberNorAnUnkeyedHashOfIt() throws Exception {
    String token = tokens.tokenise(INSTITUTION, NUMBER);
    byte[] plainHash =
        MessageDigest.getInstance("SHA-256").digest(NUMBER.getBytes(StandardCharsets.US_ASCII));
    try (Connection c = vault.superuser();
        Statement s = c.createStatement();
        ResultSet row =
            s.executeQuery(
                "SELECT lookup_hash, wrapped_key, nonce, ciphertext, row_to_json(t)::text"
                    + " FROM vault.account_tokens t WHERE account_token = '"
                    + token
                    + "'")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getBytes(1)).isNotEqualTo(plainHash);
      for (int column = 1; column <= 4; column++) {
        assertThat(new String(row.getBytes(column), StandardCharsets.ISO_8859_1))
            .doesNotContain(NUMBER)
            .doesNotContain("45678901");
      }
      assertThat(row.getString(5)).doesNotContain(NUMBER).doesNotContain("45678901");
    }
  }

  @Test
  void theWrongKeyOrMovedRowsDoNotDecrypt() throws Exception {
    String first = tokens.tokenise(INSTITUTION, "5500" + System.nanoTime());
    String second = tokens.tokenise(INSTITUTION, "5501" + System.nanoTime());
    AccountTokens otherMaster =
        new AccountTokens(
            vault.dataSource("fs_vault"),
            new PassphraseKeyProvider(Map.of("m6", TestVault.masterKey()), "m6"),
            indexKey);
    assertThatThrownBy(() -> otherMaster.detokenise(INSTITUTION, first))
        .isInstanceOf(VaultException.class);
    try (Connection c = vault.superuser();
        Statement s = c.createStatement()) {
      s.execute(
          "UPDATE vault.account_tokens b SET key_id = a.key_id, wrapped_key = a.wrapped_key,"
              + " nonce = a.nonce, ciphertext = a.ciphertext FROM vault.account_tokens a"
              + " WHERE a.account_token = '"
              + first
              + "' AND b.account_token = '"
              + second
              + "'");
    }
    assertThatThrownBy(() -> tokens.detokenise(INSTITUTION, second))
        .isInstanceOf(VaultException.class)
        .hasMessageNotContaining("5500");
  }

  @Test
  void tokensArePermanentForTheApplicationRole() throws Exception {
    tokens.tokenise(INSTITUTION, NUMBER);
    try (Connection c = vault.dataSource("fs_vault").getConnection();
        Statement s = c.createStatement()) {
      assertThatThrownBy(
              () -> s.execute("UPDATE vault.account_tokens SET account_token = account_token"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> s.execute("DELETE FROM vault.account_tokens"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void whatIsNotAnAccountNumberIsRefusedWithoutRepeatingIt() {
    for (String bad : new String[] {"", "12", "4001-2345-ABC!", "0".repeat(35), "+250788123456"}) {
      assertThatThrownBy(() -> tokens.tokenise(INSTITUTION, bad))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad.isEmpty() ? "\0" : bad));
    }
    assertThatThrownBy(
            () ->
                new AccountTokens(
                    vault.dataSource("fs_vault"),
                    new PassphraseKeyProvider(Map.of("m6", masterKey), "m6"),
                    new byte[16]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theMapWorksTheSameUnderKeyManagementService() {
    AccountTokens underKms =
        new AccountTokens(
            vault.dataSource("fs_vault"),
            new KmsKeyProvider(new InMemoryKms("kek-a"), "kek-a", Set.of("kek-a")),
            indexKey);
    String number = "6600" + System.nanoTime();
    String token = underKms.tokenise(INSTITUTION, number);
    assertThat(underKms.detokenise(INSTITUTION, token)).contains(number);
    assertThat(tokens.tokenise(INSTITUTION, number))
        .as("the blind index does not depend on the key provider")
        .isEqualTo(token);
  }

  private static int count(String sql) throws SQLException {
    try (Connection c = vault.superuser();
        Statement s = c.createStatement();
        ResultSet row = s.executeQuery(sql)) {
      row.next();
      return row.getInt(1);
    }
  }
}
