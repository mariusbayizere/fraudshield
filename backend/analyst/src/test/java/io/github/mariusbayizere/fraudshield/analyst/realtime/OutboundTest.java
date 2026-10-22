package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A session's queue and the instance budget (ADR 0115 point 8). */
class OutboundTest {

  private static Outbound.Frame frame(int bytes) {
    return new Outbound.Frame("/user/queue/alerts", "x".repeat(bytes), bytes, 1);
  }

  @Test
  @Tag("FR-04-02")
  void refusesPastEitherBoundAndCountsTheBudget() throws InterruptedException {
    InstanceBudget budget = new InstanceBudget(1_000);
    Outbound queue = new Outbound(3, 100, budget);
    assertThat(queue.offer(frame(40))).isTrue();
    assertThat(queue.offer(frame(40))).isTrue();
    assertThat(queue.offer(frame(40))).as("past the byte bound").isFalse();
    assertThat(queue.offer(frame(10))).isTrue();
    assertThat(queue.offer(frame(1))).as("past the frame bound").isFalse();
    assertThat(budget.queued()).isEqualTo(90);
    assertThat(queue.take(1, TimeUnit.MILLISECONDS).bytes()).isEqualTo(40);
    assertThat(budget.queued()).isEqualTo(50);
  }

  @Test
  @Tag("FR-04-02")
  void failedQueueDropsWhatItHoldsAndWritesTheErrorLast() throws InterruptedException {
    InstanceBudget budget = new InstanceBudget(1_000);
    Outbound queue = new Outbound(10, 1_000, budget);
    queue.offer(frame(100));
    Outbound.Frame error = StompFrames.control("ERROR\n\n\0");
    assertThat(queue.fail(error)).isTrue();
    assertThat(budget.queued()).isZero();
    assertThat(queue.offer(frame(1))).isFalse();
    assertThat(queue.fail(StompFrames.control("ERROR\n\n\0")))
        .as("the first ERROR stays")
        .isFalse();
    assertThat(queue.take(1, TimeUnit.MILLISECONDS)).isSameAs(error);
    assertThat(queue.take(1, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  @Tag("FR-04-02")
  void anIdleQueueAsksForHeartBeat() throws InterruptedException {
    Outbound queue = new Outbound(10, 1_000, new InstanceBudget(1_000));
    assertThat(queue.take(5, TimeUnit.MILLISECONDS)).isSameAs(Outbound.IDLE);
    queue.close();
    assertThat(queue.take(5, TimeUnit.MILLISECONDS)).isNull();
  }
}
