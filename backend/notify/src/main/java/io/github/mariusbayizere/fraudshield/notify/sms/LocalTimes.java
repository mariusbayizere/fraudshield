package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Local civil time with its zone abbreviation for customer messages (D-43): Rwanda and Burundi CAT
 * (UTC+2); Kenya, Tanzania, Uganda and Somalia EAT (UTC+3); South Sudan CAT; the DRC spans Kinshasa
 * (WAT, UTC+1) in the west and Lubumbashi (CAT, UTC+2) in the east, split here at 22.5°E, an
 * approximation of the provincial boundary recorded in ADR 0065.
 *
 * <p>The zone follows the transaction's currency, which the customer's institution sets; a USD or
 * EUR transaction carries no country, so its time is given in UTC rather than a guessed zone.
 */
public final class LocalTimes {

  /** Longitude west of which the DRC keeps Kinshasa time. */
  public static final double DRC_WEST_OF_LONGITUDE = 22.5;

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("HH:mm zzz", Locale.ENGLISH);

  private LocalTimes() {}

  /**
   * The zone of a transaction.
   *
   * @param currency transaction currency
   * @param longitude transaction longitude
   * @return the IANA zone
   */
  public static ZoneId zone(CurrencyCode currency, double longitude) {
    return switch (currency) {
      case RWF -> ZoneId.of("Africa/Kigali");
      case BIF -> ZoneId.of("Africa/Bujumbura");
      case SSP -> ZoneId.of("Africa/Juba");
      case KES -> ZoneId.of("Africa/Nairobi");
      case TZS -> ZoneId.of("Africa/Dar_es_Salaam");
      case UGX -> ZoneId.of("Africa/Kampala");
      case SOS -> ZoneId.of("Africa/Mogadishu");
      case CDF ->
          longitude < DRC_WEST_OF_LONGITUDE
              ? ZoneId.of("Africa/Kinshasa")
              : ZoneId.of("Africa/Lubumbashi");
      default -> ZoneId.of("UTC");
    };
  }

  /**
   * 24-hour local time with the zone abbreviation, as the notification contract requires.
   *
   * @param at the instant
   * @param zone the zone
   * @return for example {@code 10:15 CAT}
   */
  public static String format(Instant at, ZoneId zone) {
    return FORMAT.format(at.atZone(zone));
  }
}
