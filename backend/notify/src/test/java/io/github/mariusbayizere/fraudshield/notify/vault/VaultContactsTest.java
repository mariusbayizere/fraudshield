package io.github.mariusbayizere.fraudshield.notify.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.notify.sms.ContactDirectory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * D-20: the vault keeps the only customer phone numbers and account numbers, encrypted with
 * AES-256-GCM under per-row data keys, in its own instance with its own role. Nothing that can read
 * a transaction can read a phone number.
 */
@Tag("requires-docker")
@Tag("D-20")
@Tag("NFR-SEC-03")
class VaultContactsTest {

  private static final UUID INSTITUTION = UUID.randomUUID();
  private static final String ACCOUNT = "tok_VaultAccountAaaaBbbbCc1Z";
  private static final ContactDirectory.Contact CONTACT =
      new ContactDirectory.Contact("+250788123456", "rw", "***4821");

  private static TestVault vault;
  private static String masterKey;
  private static VaultContacts contacts;

  @BeforeAll
  static void start() {
    vault = new TestVault();
    masterKey = TestVault.masterKey();
    contacts =
        new VaultContacts(
            vault.dataSource("fs_vault"), new PassphraseKeyProvider(Map.of("m6", masterKey), "m6"));
  }

  @AfterAll
  static void stop() {
    vault.close();
  }

  @Test
  void contactsGoInAndComeBackAndTheStoredBytesAreNotTheNumber() throws Exception {
    contacts.store(INSTITUTION, ACCOUNT, CONTACT);
    assertThat(contacts.find(INSTITUTION, ACCOUNT)).contains(CONTACT);
    assertThat(contacts.find(INSTITUTION, "tok_NeverEnrolledAaaaBbbbCc1")).isEmpty();
    assertThat(contacts.find(UUID.randomUUID(), ACCOUNT)).as("another institution").isEmpty();
    // Any token the contracts accept can be enrolled, not only the 24-character ones.
    String longest = "tok_" + "L".repeat(64);
    contacts.store(INSTITUTION, longest, CONTACT);
    assertThat(contacts.find(INSTITUTION, longest)).contains(CONTACT);

    try (Connection c = vault.superuser();
        Statement s = c.createStatement();
        ResultSet row =
            s.executeQuery(
                "SELECT ciphertext, wrapped_key, key_id, octet_length(nonce)"
                    + " FROM vault.contacts")) {
      assertThat(row.next()).isTrue();
      String stored = new String(row.getBytes(1), StandardCharsets.ISO_8859_1);
      assertThat(stored).doesNotContain("+250788123456", "788123456", "4821", "rw");
      assertThat(row.getBytes(2)).hasSizeGreaterThan(32);
      assertThat(row.getString(3)).isEqualTo("m6");
      assertThat(row.getInt(4)).isEqualTo(12);
    }
  }

  @Test
  void replacingContactsKeepsOneRowWithTheLatestDetails() {
    String account = "tok_ReplacedAccountAaaaBbb1Z";
    contacts.store(INSTITUTION, account, CONTACT);
    ContactDirectory.Contact moved = new ContactDirectory.Contact("+250788999999", "en", "***9999");
    contacts.store(INSTITUTION, account, moved);
    assertThat(contacts.find(INSTITUTION, account)).contains(moved);
    assertThat(count("SELECT count(*) FROM vault.contacts WHERE account_token = '" + account + "'"))
        .isEqualTo(1);
  }

  @Test
  void rowsMovedToAnotherAccountOrReadWithAnotherKeyDoNotDecrypt() throws Exception {
    String source = "tok_SourceAccountAaaaBbbbC1Z";
    String target = "tok_TargetAccountAaaaBbbbC1Z";
    contacts.store(INSTITUTION, source, CONTACT);
    try (Connection c = vault.superuser();
        PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO vault.contacts (institution_id, account_token, key_id, wrapped_key,"
                    + " nonce, ciphertext) SELECT institution_id, ?, key_id, wrapped_key, nonce,"
                    + " ciphertext FROM vault.contacts WHERE account_token = ?")) {
      s.setString(1, target);
      s.setString(2, source);
      s.executeUpdate();
    }
    // The account token is authenticated data: the copied row cannot answer for another account.
    assertThatThrownBy(() -> contacts.find(INSTITUTION, target))
        .isInstanceOf(VaultException.class)
        .hasMessageContaining("does not verify");

    VaultContacts withAnotherKey =
        new VaultContacts(
            vault.dataSource("fs_vault"),
            new PassphraseKeyProvider(Map.of("m6", TestVault.masterKey()), "m6"));
    assertThatThrownBy(() -> withAnotherKey.find(INSTITUTION, source))
        .isInstanceOf(VaultException.class);

    VaultContacts withoutTheKey =
        new VaultContacts(
            vault.dataSource("fs_vault"),
            new PassphraseKeyProvider(Map.of("m9", TestVault.masterKey()), "m9"));
    assertThatThrownBy(() -> withoutTheKey.find(INSTITUTION, source))
        .isInstanceOf(VaultException.class)
        .hasMessageContaining("no master key with id m6");
  }

  @Test
  void theVaultRoleMaySeeContactsAndNothingElseMayConnect() throws Exception {
    // The main database's roles do not exist here at all (D-20): no grant can be forgotten.
    for (String role : java.util.List.of("fs_app", "fs_app_readonly", "fs_compliance_ro")) {
      assertThat(count("SELECT count(*) FROM pg_roles WHERE rolname = '" + role + "'"))
          .as(role)
          .isZero();
    }
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.table_privileges"
                    + " WHERE table_schema = 'vault' AND grantee = 'PUBLIC'"))
        .isZero();
    // fs_vault reads and writes contacts, and cannot change the schema.
    try (Connection c = vault.dataSource("fs_vault").getConnection();
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("DROP TABLE vault.contacts"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> s.execute("DELETE FROM vault.contacts"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void theApplicationRoleIsRefusedEvenIfSomeoneCreatesItHere() throws Exception {
    // D-20: the analyst-facing application role has no way into the vault. It does not exist
    // here; and if it were created by mistake, CONNECT is revoked from PUBLIC, so it still could
    // not open a session, let alone read a contact.
    String url;
    try (Connection c = vault.superuser()) {
      url = c.getMetaData().getURL();
    }
    assertThatThrownBy(() -> java.sql.DriverManager.getConnection(url, "fs_app", "anything"))
        .isInstanceOf(SQLException.class);
    try (Connection c = vault.superuser();
        Statement s = c.createStatement()) {
      s.execute("CREATE ROLE fs_app LOGIN PASSWORD 'mistaken-grant-1'");
    }
    try {
      assertThatThrownBy(
              () -> java.sql.DriverManager.getConnection(url, "fs_app", "mistaken-grant-1"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for database");
    } finally {
      try (Connection c = vault.superuser();
          Statement s = c.createStatement()) {
        s.execute("DROP ROLE fs_app");
      }
    }
  }

  @Test
  void contactsRoundTripUnderKeyManagementServiceAndNotUnderAnotherKey() {
    InMemoryKms kms = new InMemoryKms("kek-a", "kek-b");
    VaultContacts underKms =
        new VaultContacts(
            vault.dataSource("fs_vault"), new KmsKeyProvider(kms, "kek-a", Set.of("kek-a")));
    String account = "tok_KmsAccountAaaaBbbbCcc12Z";
    underKms.store(INSTITUTION, account, CONTACT);
    assertThat(underKms.find(INSTITUTION, account)).contains(CONTACT);
    assertThatThrownBy(() -> contacts.find(INSTITUTION, account))
        .as("a deployment that does not hold the row's key")
        .isInstanceOf(VaultException.class);
    VaultContacts otherKek =
        new VaultContacts(
            vault.dataSource("fs_vault"), new KmsKeyProvider(kms, "kek-b", Set.of("kek-b")));
    assertThatThrownBy(() -> otherKek.find(INSTITUTION, account))
        .isInstanceOf(VaultException.class);
  }

  @Test
  void masterKeysAreCheckedWhenTheProviderIsBuilt() {
    assertThatThrownBy(() -> new PassphraseKeyProvider(Map.of("m6", "not base64!"), "m6"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base64");
    assertThatThrownBy(
            () ->
                new PassphraseKeyProvider(
                    Map.of("m6", java.util.Base64.getEncoder().encodeToString(new byte[16])), "m6"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> new PassphraseKeyProvider(Map.of("m6", masterKey), "m9"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Optional.of(new PassphraseKeyProvider(Map.of("m6", masterKey), "m6").newDataKey()))
        .get()
        .satisfies(
            key -> {
              assertThat(key.keyId()).isEqualTo("m6");
              assertThat(key.key().getEncoded()).hasSize(32);
              assertThat(key.wrapped()).hasSizeGreaterThan(32);
            });
  }

  @Test
  void resolvedContactsNeverPrintTheNumber() {
    assertThat(CONTACT.toString()).doesNotContain("+250788123456").contains("redacted");
  }

  private static int count(String sql) {
    try (Connection c = vault.superuser();
        Statement s = c.createStatement();
        ResultSet row = s.executeQuery(sql)) {
      row.next();
      return row.getInt(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
