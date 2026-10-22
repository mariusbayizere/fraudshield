package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.OptionalLong;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The fan-out's lag metric never fails the consumer (code review 2, MAJOR 2). */
class FanOutTest {

  @Test
  @Tag("FR-04-02")
  @SuppressWarnings("unchecked")
  void lagThatCannotBeReadIsCountedAndTheConsumerStaysHealthy() {
    RealtimeSettings settings = RealtimeSettings.defaults();
    FanOut fanOut =
        new FanOut(
            null,
            new AlertFrames(),
            mock(StaffNames.class),
            new SessionRegistry(settings, new InstanceBudget(1_000), System::nanoTime),
            institution -> {},
            settings,
            System::nanoTime,
            partition -> {});
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    TopicPartition slow = new TopicPartition("fs.alerts.high", 0);
    TopicPartition known = new TopicPartition("fs.alerts.medium", 0);
    when(consumer.currentLag(slow)).thenThrow(new TimeoutException("slow broker"));
    when(consumer.currentLag(known)).thenReturn(OptionalLong.of(7));
    fanOut.measureLag(consumer, Set.of(slow, known));
    assertThat(fanOut.lagMisses()).isEqualTo(1);
    assertThat(fanOut.lag()).containsEntry(known, 7L);
  }
}
