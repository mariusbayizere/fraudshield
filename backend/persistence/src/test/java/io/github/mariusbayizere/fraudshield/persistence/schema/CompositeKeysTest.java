package io.github.mariusbayizere.fraudshield.persistence.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The seeder's composite keys have value semantics, as {@code @IdClass} requires: Hibernate sets
 * their fields by reflection and compares keys to find an entity in the persistence context.
 */
class CompositeKeysTest {

  private static final UUID BANK = UUID.randomUUID();

  private static <K> K key(Supplier<K> empty, Object... fieldsAndValues) {
    K key = empty.get();
    for (int i = 0; i < fieldsAndValues.length; i += 2) {
      ReflectionTestUtils.setField(key, (String) fieldsAndValues[i], fieldsAndValues[i + 1]);
    }
    return key;
  }

  /**
   * Equal keys from equal fields, and a different key when any one field differs.
   *
   * @param empty the key's no-argument constructor
   * @param fields field names and values, alternating
   * @param others for each field, in the same order, a different value
   */
  private static <K> void valueSemantics(Supplier<K> empty, Object[] fields, Object... others) {
    K a = key(empty, fields);
    K b = key(empty, fields);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    for (int i = 0; i < others.length; i++) {
      Object[] changed = fields.clone();
      changed[2 * i + 1] = others[i];
      assertThat(a).as("differs in %s", fields[2 * i]).isNotEqualTo(key(empty, changed));
    }
    assertThat(a.equals(null)).isFalse();
    assertThat(a.equals("not a key")).isFalse();
  }

  @Test
  void riskThresholdKeysCompareByInstitutionVersionAndChannel() {
    valueSemantics(
        RiskThresholdEntity.Key::new,
        new Object[] {"institutionId", BANK, "version", 1L, "channel", "CARD"},
        UUID.randomUUID(),
        2L,
        "USSD");
  }

  @Test
  void thresholdVersionKeysCompareByInstitutionAndVersion() {
    valueSemantics(
        RiskThresholdVersionEntity.Key::new,
        new Object[] {"institutionId", BANK, "version", 1L},
        UUID.randomUUID(),
        2L);
  }

  @Test
  void breakerSettingsKeysCompareByInstitutionAndVersion() {
    valueSemantics(
        BreakerSettingsEntity.Key::new,
        new Object[] {"institutionId", BANK, "version", 1L},
        UUID.randomUUID(),
        2L);
  }
}
