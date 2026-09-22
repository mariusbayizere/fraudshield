package io.github.mariusbayizere.fraudshield.decision.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Pre-computed account state read before scoring (FR-02-09); the domain form of the scoring
 * contract's {@code AccountContext}. Nullable fields are absent when there is no history or no
 * source, and the scorer applies each feature's NaN rule (D-04).
 *
 * @param txCount60s transactions in the trailing 60 seconds, the scored one excluded
 * @param txCount1h transactions in the trailing hour
 * @param txCount24h transactions in the trailing 24 hours
 * @param txCount7d transactions in the trailing 7 days
 * @param amountSum24hRwf RWF sum over 24 hours
 * @param amountSum7dRwf RWF sum over 7 days
 * @param uniqueCounterparties24h distinct counterparties over 24 hours
 * @param meanHourlyCount30d mean hourly count over the observed part of the prior 30 days; NaN when
 *     the account's durable first-seen is unknown, so the ratio fails closed (PB-37)
 * @param amountMedian90dRwf median amount over 90 days
 * @param amountMad90dRwf median absolute deviation over 90 days
 * @param amountMax90dRwf maximum amount over 90 days
 * @param historyCount90d transactions over 90 days
 * @param lastLocation location of the previous transaction
 * @param lastTransactionAt time of the previous transaction
 * @param homeCentroid90d median location over 90 days
 * @param countriesSeen counterparty countries seen for the account
 * @param accountAgeDays days since the account was opened (PB-37); null when not supplied
 * @param kycTier KYC tier in force; null when not supplied
 * @param daysSinceSimSwap days since the last SIM swap from the MNO signal; null when unavailable
 * @param daysSincePreviousActivity days since the previous transaction
 * @param counterpartyNewForAccount whether the account has not paid this counterparty before
 * @param counterpartyAccountAgeDays the counterparty account's age; null when not supplied
 * @param counterpartyUniqueSenders24h distinct senders to the counterparty over 24 hours
 * @param counterpartyConfirmedFraud90d confirmed-fraud labels on the counterparty, lagged
 * @param txCountToCounterparty30d transactions from this account to the counterparty, 30 days
 * @param device device state; null when the transaction has no fingerprint (D-04)
 * @param agent agent state; null unless AGENT_BANKING
 * @param geoCellFraudRate30d lagged fraud rate of the H3 cell; null when not available
 * @param accountsSharingDeviceOrPhone accounts sharing the device or phone
 * @param volumeRampRatio7d recent volume ramp; null without enough history
 */
public record AccountHistory(
    int txCount60s,
    int txCount1h,
    int txCount24h,
    int txCount7d,
    BigDecimal amountSum24hRwf,
    BigDecimal amountSum7dRwf,
    int uniqueCounterparties24h,
    double meanHourlyCount30d,
    BigDecimal amountMedian90dRwf,
    BigDecimal amountMad90dRwf,
    BigDecimal amountMax90dRwf,
    int historyCount90d,
    GeoPoint lastLocation,
    Instant lastTransactionAt,
    GeoPoint homeCentroid90d,
    List<String> countriesSeen,
    Integer accountAgeDays,
    Integer kycTier,
    Integer daysSinceSimSwap,
    Integer daysSincePreviousActivity,
    boolean counterpartyNewForAccount,
    Integer counterpartyAccountAgeDays,
    int counterpartyUniqueSenders24h,
    int counterpartyConfirmedFraud90d,
    int txCountToCounterparty30d,
    DeviceHistory device,
    AgentHistory agent,
    Double geoCellFraudRate30d,
    int accountsSharingDeviceOrPhone,
    Double volumeRampRatio7d) {

  /** Requires the sums and copies the list. */
  public AccountHistory {
    Objects.requireNonNull(amountSum24hRwf, "amountSum24hRwf");
    Objects.requireNonNull(amountSum7dRwf, "amountSum7dRwf");
    countriesSeen = List.copyOf(countriesSeen);
  }

  /**
   * State of the transaction's device.
   *
   * @param newForAccount whether the account has not used the device before
   * @param accountsPerDevice7d accounts seen on the device over 7 days
   * @param deviceChanges24h device changes for the account over 24 hours
   * @param deviceAgeDays days since the device was first seen; null when unknown
   */
  public record DeviceHistory(
      boolean newForAccount,
      int accountsPerDevice7d,
      int deviceChanges24h,
      Integer deviceAgeDays) {}

  /**
   * State of the transaction's agent.
   *
   * @param floatUtilisationRatio float utilisation; null without an agent standing record
   * @param cashoutCount1h cash-outs at the agent over one hour
   * @param uniqueCustomers1h distinct customers at the agent over one hour
   * @param distanceFromRegisteredKm distance from the registered premises; null when unknown
   */
  public record AgentHistory(
      Double floatUtilisationRatio,
      int cashoutCount1h,
      int uniqueCustomers1h,
      Double distanceFromRegisteredKm) {}

  /**
   * An account with no history at all.
   *
   * @param counterpartyNew whether the counterparty is new (always true without history)
   * @return empty history; the mean hourly count is NaN because first-seen is unknown
   */
  public static AccountHistory empty(boolean counterpartyNew) {
    return new AccountHistory(
        0,
        0,
        0,
        0,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        Double.NaN,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        counterpartyNew,
        null,
        0,
        0,
        0,
        null,
        null,
        null,
        0,
        null);
  }
}
