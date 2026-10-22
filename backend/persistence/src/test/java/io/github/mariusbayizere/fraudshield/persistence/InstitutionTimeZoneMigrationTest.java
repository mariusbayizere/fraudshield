package io.github.mariusbayizere.fraudshield.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * V82's backfill (ADR 0117 point 1): institutions that exist before it take their zone from a fixed
 * map of their country, and a country outside the map fails the migration, naming the country,
 * instead of guessing a day boundary.
 */
@Tag("requires-docker")
@Tag("FR-04-11")
@Tag("UX-DASH-01")
class InstitutionTimeZoneMigrationTest {

  /** The last migration before V82. */
  private static final String BEFORE_V82 = "81";

  @Test
  void existingInstitutionsTakeTheirCountrysZone() throws SQLException {
    TestDatabase db = TestDatabase.createAt(BEFORE_V82);
    insert(
        db,
        "('rw-bank', 'Synthetic RW', 'RW'), ('ke-bank', 'Synthetic KE', 'KE'),"
            + " ('ug-bank', 'Synthetic UG', 'UG'), ('tz-bank', 'Synthetic TZ', 'TZ'),"
            + " ('cd-bank', 'Synthetic CD', 'CD')");
    db.migrate(null);
    Map<String, String> zones = new LinkedHashMap<>();
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT code, time_zone FROM fraudshield.institutions ORDER BY code")) {
      while (rows.next()) {
        zones.put(rows.getString(1), rows.getString(2));
      }
    }
    assertThat(zones)
        .containsExactly(
            Map.entry("cd-bank", "Africa/Kinshasa"),
            Map.entry("ke-bank", "Africa/Nairobi"),
            Map.entry("rw-bank", "Africa/Kigali"),
            Map.entry("tz-bank", "Africa/Dar_es_Salaam"),
            Map.entry("ug-bank", "Africa/Kampala"));
  }

  @Test
  void countryOutsideTheMapFailsTheMigrationAndNamesIt() throws SQLException {
    TestDatabase db = TestDatabase.createAt(BEFORE_V82);
    insert(db, "('rw-bank', 'Synthetic RW', 'RW'), ('za-bank', 'Synthetic ZA', 'ZA')");
    assertThatThrownBy(() -> db.migrate(null))
        .hasMessageContaining("no time zone is mapped for institutions in country ZA");
  }

  private static void insert(TestDatabase db, String values) throws SQLException {
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement()) {
      statement.execute(
          "INSERT INTO fraudshield.institutions (code, name, country) VALUES " + values);
    }
  }
}
