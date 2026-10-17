package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * I29: the checkpoint metadata follows docs/architecture/decision-fact-ordering.md, W-e2 exactly;
 * and the three states of {@code fs-transaction-id} (5.1), whose strict parse I21 relies on.
 */
@Tag("NFR-REL-01")
class CheckpointTest {

  @Test
  void v3IsWrittenAndReadBackFieldForField() {
    ProviderResult sent = ProviderResult.sent("rw", true, "ATXid_1");
    Checkpoint checkpoint = new Checkpoint(42, 360_000, 1_000_000, 3, 125_000, sent);
    String text = checkpoint.text();
    assertThat(text)
        .isEqualTo(
            "fs-wait:v3 offset=42 spent_ms=360000 at=1000000 f4_count=3 f4_ms=125000"
                + " result=SENT:rw:1:"
                + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("ATXid_1".getBytes(StandardCharsets.UTF_8)));
    assertThat(Checkpoint.parse(text)).contains(checkpoint);

    Checkpoint failed = new Checkpoint(7, 0, 5, 0, 0, ProviderResult.failed("en", false, false));
    assertThat(failed.text())
        .isEqualTo("fs-wait:v3 offset=7 spent_ms=0 at=5 f4_count=0 f4_ms=0 result=FAILED:en:0");
    assertThat(Checkpoint.parse(failed.text())).contains(failed);
  }

  @Test
  void checkpointsWithNothingToCarryAreNoMetadata() {
    assertThat(new Checkpoint(1, 0, 5, 0, 0, null).text()).isEmpty();
    assertThat(new Checkpoint(1, 0, 5, 1, 0, null).text())
        .as("retry state alone is carried, whatever the budget (W-e)")
        .startsWith("fs-wait:v3 ");
  }

  @Test
  void v2IsReadWithNoRetryStateAndNoKeptResult() {
    assertThat(Checkpoint.parse("fs-wait:v2 offset=9 spent_ms=1200 at=77"))
        .contains(new Checkpoint(9, 1200, 77, 0, 0, null));
  }

  @Test
  void anythingElseIsReadAsNoMetadata() {
    assertThat(Checkpoint.parse(null)).isEmpty();
    assertThat(Checkpoint.parse("")).isEmpty();
    assertThat(Checkpoint.parse("fs-wait:v4 offset=1")).isEmpty();
    assertThat(
            Checkpoint.parse(
                "fs-wait:v3 offset=01 spent_ms=0 at=0 f4_count=0 f4_ms=0" + " result=none"))
        .as("leading zeros")
        .isEmpty();
    assertThat(
            Checkpoint.parse(
                "fs-wait:v3 offset=1 spent_ms=0 at=0 f4_count=0 f4_ms=0 result=SENT:en:2:AA"))
        .as("a link flag that is neither 0 nor 1")
        .isEmpty();
    assertThat(
            Checkpoint.parse("fs-wait:v3 offset=1 spent_ms=0 at=0 f4_ms=0 f4_count=0 result=none"))
        .as("fields out of order")
        .isEmpty();
  }

  @Test
  void referencesAreKeptWithinTheColumnsLimit() {
    String long200 = "r".repeat(200);
    ProviderResult cut = ProviderResult.sent("en", false, long200);
    assertThat(cut.reference()).hasSize(128).endsWith("~").startsWith("r".repeat(127));
    assertThat(ProviderResult.parse(cut.text())).contains(cut);

    assertThat(ProviderResult.sent("en", false, "").reference())
        .as("an empty reference is recorded as null")
        .isNull();
    assertThat(ProviderResult.parse("SENT:en:0:")).contains(ProviderResult.sent("en", false, null));

    assertThat(ProviderResult.sent("en", false, "a\u0000b").reference()).isEqualTo("a�b");

    // Code points, not chars: 128 emoji fit; 129 are cut to 127 and the mark.
    String emoji = "😀";
    assertThat(ProviderResult.sent("en", false, emoji.repeat(128)).reference())
        .isEqualTo(emoji.repeat(128));
    assertThat(ProviderResult.sent("en", false, emoji.repeat(129)).reference())
        .isEqualTo(emoji.repeat(127) + "~");
  }

  @Test
  void theTransactionHeaderHasThreeStates() {
    RecordHeaders none = new RecordHeaders();
    assertThat(TransactionHeader.of(none, "fs-transaction-id")).isEqualTo(TransactionHeader.ABSENT);

    String id = "0f8fad5b-d9cb-469f-a165-70867728950e";
    assertThat(TransactionHeader.of(headers(id), "fs-transaction-id").transaction())
        .contains(java.util.UUID.fromString(id));

    assertThat(TransactionHeader.of(headers("1-2-3-4-5"), "fs-transaction-id"))
        .as("UUID.fromString would accept a short form; the strict parse does not")
        .isEqualTo(TransactionHeader.MALFORMED);
    assertThat(TransactionHeader.of(headers(""), "fs-transaction-id"))
        .isEqualTo(TransactionHeader.MALFORMED);
    assertThat(TransactionHeader.of(headers("t-1"), "fs-transaction-id"))
        .isEqualTo(TransactionHeader.MALFORMED);
    assertThat(TransactionHeader.of(headers(id, id), "fs-transaction-id"))
        .as("a repeated header is malformed, even with equal values")
        .isEqualTo(TransactionHeader.MALFORMED);
    RecordHeaders nullValued = new RecordHeaders();
    nullValued.add("fs-transaction-id", null);
    assertThat(TransactionHeader.of(nullValued, "fs-transaction-id"))
        .isEqualTo(TransactionHeader.MALFORMED);
    assertThat(TransactionHeader.ABSENT.transaction()).isEqualTo(Optional.empty());
  }

  private static RecordHeaders headers(String... values) {
    RecordHeaders headers = new RecordHeaders();
    for (String value : values) {
      headers.add("fs-transaction-id", value.getBytes(StandardCharsets.UTF_8));
    }
    return headers;
  }
}
