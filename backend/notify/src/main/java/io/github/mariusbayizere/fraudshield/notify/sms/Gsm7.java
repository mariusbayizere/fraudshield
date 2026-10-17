package io.github.mariusbayizere.fraudshield.notify.sms;

/**
 * The GSM 03.38 7-bit alphabet (E.7: customer SMS are GSM-7 only, so no message silently switches
 * to UCS-2 and shrinks to 70 characters on a feature phone).
 */
public final class Gsm7 {

  /** Single-segment limit in septets. */
  public static final int SINGLE_SEGMENT = 160;

  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
  private static final String EXTENSION = "^{}\\[~]|€\f";

  private Gsm7() {}

  /**
   * Septets a text occupies, or -1 if it has a character outside GSM-7.
   *
   * @param text the text
   * @return septet count (extension characters count two), or -1
   */
  public static int septets(String text) {
    int septets = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (BASIC.indexOf(c) >= 0) {
        septets += 1;
      } else if (EXTENSION.indexOf(c) >= 0) {
        septets += 2;
      } else {
        return -1;
      }
    }
    return septets;
  }

  /**
   * Whether a text is one GSM-7 segment.
   *
   * @param text the text
   * @return true when every character is GSM-7 and it fits 160 septets
   */
  public static boolean fitsOneSegment(String text) {
    int septets = septets(text);
    return septets >= 0 && septets <= SINGLE_SEGMENT;
  }
}
