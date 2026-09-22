package io.github.mariusbayizere.fraudshield.decision.testing;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThreshold;
import io.github.mariusbayizere.fraudshield.common.config.ChannelThresholds;
import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Synthetic test data: tokens only, no personal data (G.5). */
public final class Fixtures {

  /** A fixed synthetic institution. */
  public static final UUID INSTITUTION = UUID.fromString("0f8e5a4c-1b2d-4c3e-8f9a-0b1c2d3e4f50");

  /** A synthetic account token. */
  public static final String ACCOUNT = "tok_AccountAaaaBbbbCcccDddd01";

  /** A synthetic counterparty token. */
  public static final String COUNTERPARTY = "tok_CounterpartyEeeeFfffGggg1";

  /** A fixed instant. */
  public static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

  private Fixtures() {}

  /**
   * The SRS default thresholds (0.60 / 0.85) on every channel.
   *
   * @return thresholds
   */
  public static ChannelThresholds defaultThresholds() {
    Map<Channel, ChannelThreshold> map = new EnumMap<>(Channel.class);
    for (Channel channel : Channel.values()) {
      map.put(channel, threshold());
    }
    return new ChannelThresholds(map);
  }

  /**
   * One channel's SRS default threshold.
   *
   * @return 0.60 / 0.85, release with timeout label
   */
  public static ChannelThreshold threshold() {
    return ChannelThreshold.of("0.60", "0.85", MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL);
  }

  /**
   * A MOBILE_MONEY transaction of the given RWF amount.
   *
   * @param amountRwf amount
   * @return the transaction
   */
  public static Transaction transaction(String amountRwf) {
    return transaction(UUID.randomUUID(), ACCOUNT, amountRwf, Channel.MOBILE_MONEY);
  }

  /**
   * A transaction.
   *
   * @param id transaction id
   * @param account account token
   * @param amountRwf amount in RWF
   * @param channel channel
   * @return the transaction
   */
  public static Transaction transaction(
      UUID id, String account, String amountRwf, Channel channel) {
    return new Transaction(
        INSTITUTION,
        id,
        account,
        COUNTERPARTY,
        Money.of(amountRwf, CurrencyCode.RWF),
        new BigDecimal(amountRwf),
        channel,
        "4829",
        -1.9441,
        30.0619,
        channel == Channel.USSD ? null : "tok_DeviceHhhhIiiiJjjjKkkk01",
        channel == Channel.AGENT_BANKING ? "tok_AgentLlllMmmmNnnnOoooPp01" : null,
        "RW",
        NOW,
        NOW);
  }
}
