package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** FX rates for normalising amounts to RWF at the transaction date (E.2). */
public interface FxRatePort {

  /**
   * RWF per unit of a currency, as of a date: the latest rate dated on or before it.
   *
   * @param currency currency
   * @param date the transaction's UTC date
   * @return the rate, or empty if no rate is known up to that date
   */
  Optional<BigDecimal> rwfPerUnit(CurrencyCode currency, LocalDate date);
}
