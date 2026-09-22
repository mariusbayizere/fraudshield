package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.decision.domain.AccountHistory;
import io.github.mariusbayizere.fraudshield.decision.domain.FeatureContribution;
import io.github.mariusbayizere.fraudshield.decision.domain.ReasonCodes;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Whether a blocked customer may lift the block themselves through the SMS link (D-25).
 *
 * <p>SIM-swap takeover is the dominant fraud, and in it the fraudster holds the victim's SIM and
 * receives the link. Self-service is therefore refused when the SIM was swapped less than 7 days
 * ago <em>or the signal is unavailable</em>, when the device changed in the last 24 hours, when the
 * calibrated score is at least 0.95, when the model's reasons point at account takeover, and when
 * the model was unavailable (no score to judge). The customer is then told to call the institution
 * or visit a branch or agent.
 */
public final class SelfServicePolicy {

  /** SIM swaps more recent than this disable self-service. */
  public static final int SIM_SWAP_DAYS = 7;

  /** Scores at or above this disable self-service. */
  public static final double SCORE_LIMIT = 0.95;

  private static final Set<String> TAKEOVER =
      Set.of("RECENT_SIM_SWAP", "NEW_DEVICE", "DEVICE_CHANGES", "SHARED_DEVICE");

  /** Why self-service was refused. */
  public enum Refusal {
    /** SIM swapped within 7 days, or the MNO signal is unavailable. */
    SIM_SWAP_RECENT_OR_UNKNOWN,
    /** A new or changed device within 24 hours. */
    DEVICE_CHANGED,
    /** Calibrated score at least 0.95. */
    SCORE_TOO_HIGH,
    /** The model's reasons indicate account takeover. */
    TAKEOVER_SIGNALS,
    /** The rule-based fallback decided; there is no score to judge. */
    MODEL_UNAVAILABLE
  }

  /**
   * The verdict.
   *
   * @param allowed whether the verification link may be sent
   * @param refusals every reason it may not, empty when allowed
   */
  public record Eligibility(boolean allowed, List<Refusal> refusals) {
    /** Copies the refusals. */
    public Eligibility {
      refusals = List.copyOf(refusals);
    }
  }

  private SelfServicePolicy() {}

  /**
   * Evaluates the D-25 conditions.
   *
   * @param scoring the scoring the block was based on
   * @param history the account state before the transaction
   * @return eligibility with every refusal reason
   */
  public static Eligibility evaluate(Scoring scoring, AccountHistory history) {
    List<Refusal> refusals = new ArrayList<>();
    if (history.daysSinceSimSwap() == null || history.daysSinceSimSwap() < SIM_SWAP_DAYS) {
      refusals.add(Refusal.SIM_SWAP_RECENT_OR_UNKNOWN);
    }
    if (history.device() != null
        && (history.device().newForAccount() || history.device().deviceChanges24h() > 0)) {
      refusals.add(Refusal.DEVICE_CHANGED);
    }
    switch (scoring) {
      case Scoring.Model model -> {
        if (model.ensembleScore() >= SCORE_LIMIT) {
          refusals.add(Refusal.SCORE_TOO_HIGH);
        }
        List<String> reasons =
            model.topContributions().stream()
                .filter(FeatureContribution::increasesRisk)
                .map(c -> ReasonCodes.forFeature(c.feature()))
                .toList();
        if (reasons.stream().anyMatch(TAKEOVER::contains)) {
          refusals.add(Refusal.TAKEOVER_SIGNALS);
        }
      }
      case Scoring.Fallback fallback -> {
        refusals.add(Refusal.MODEL_UNAVAILABLE);
        if (fallback.reasonCodes().stream().anyMatch(TAKEOVER::contains)) {
          refusals.add(Refusal.TAKEOVER_SIGNALS);
        }
      }
    }
    return new Eligibility(refusals.isEmpty(), refusals);
  }
}
