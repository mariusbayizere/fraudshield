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

  private static <K> void valueSemantics(Supplier<K> empty, Object[] same, Object[] other) {
    K a = key(empty, same);
    K b = key(empty, same);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(key(empty, other));
    assertThat(a.equals(null)).isFalse();
    assertThat(a.equals("not a key")).isFalse();
  }

  @Test
  void riskThresholdKeysCompareByInstitutionVersionAndChannel() {
    valueSemantics(
        RiskThresholdEntity.Key::new,
        new Object[] {"institutionId", BANK, "version", 1L, "channel", "CARD"},
        new Object[] {"institutionId", BANK, "version", 1L, "channel", "USSD"});
  }

  @Test
  void thresholdVersionKeysCompareByInstitutionAndVersion() {
    valueSemantics(
        RiskThresholdVersionEntity.Key::new,
        new Object[] {"institutionId", BANK, "version", 1L},
        new Object[] {"institutionId", BANK, "version", 2L});
  }

  @Test
  void breakerSettingsKeysCompareByInstitutionAndVersion() {
    valueSemantics(
        BreakerSettingsEntity.Key::new,
        new Object[] {"institutionId", BANK, "version", 1L},
        new Object[] {"institutionId", UUID.randomUUID(), "version", 1L});
  }
}
