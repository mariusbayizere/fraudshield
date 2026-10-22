package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.decision.application.port.HoldOwnerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.sql.SQLException;
import java.util.Objects;

/**
 * A hold's TIMEOUT request (decision-owner contract §6.3): run in its own transaction on the
 * owner's runner, then recorded after the commit (§4.6).
 */
public final class OwnerHoldTimeouts implements HoldOwnerPort {

  private final OwnerTransactions transactions;
  private final DecisionOwner owner;
  private final RequestRecorder recorder;

  /**
   * Creates the adapter.
   *
   * @param transactions the runner
   * @param owner the owner
   * @param recorder records an applied state after the commit
   */
  public OwnerHoldTimeouts(
      OwnerTransactions transactions, DecisionOwner owner, RequestRecorder recorder) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  @Override
  public Answer timeOut(HoldSchedulePort.DueHold hold) {
    OwnerRequest.Timeout request =
        new OwnerRequest.Timeout(
            hold.eventId(),
            hold.eventId() == null ? null : DecisionValue.HOLD,
            hold.deadline(),
            hold.decidedAt(),
            hold.reasonCodes(),
            hold.policy(),
            hold.channel());
    DecisionOwner.Result result;
    try {
      result =
          transactions.inTenant(
              hold.institutionId(),
              c -> owner.submit(c, hold.institutionId(), hold.transactionId(), request));
    } catch (SQLException e) {
      throw new IllegalStateException("the timeout request failed", e);
    }
    if (result.replay()) {
      return new Answer(Next.DROP, null, null);
    }
    return switch (result.outcome()) {
      case APPLIED -> {
        recorder.record(hold.institutionId(), result);
        yield new Answer(Next.APPLIED, null, result.state());
      }
      case DEFERRED -> new Answer(Next.RETRY, null, null);
      case REFUSED ->
          result.reschedule() != null
              ? new Answer(Next.RESCHEDULE, result.reschedule(), null)
              : new Answer(Next.DROP, null, null);
      default -> new Answer(Next.DROP, null, null);
    };
  }
}
