package io.github.mariusbayizere.fraudshield.common.identity;

import java.text.Normalizer;
import java.util.Objects;
import java.util.Optional;

/**
 * A person's first or last name, normalised to Unicode NFC (ADR 0013).
 *
 * <p>Real names across the East African Community include letters outside Latin-1 (Kikuyu "Ngũgĩ"),
 * French accents, apostrophes ("N'Dri"), hyphens and multiple given names. The rule: after NFC
 * normalisation, no leading or trailing whitespace; 2 to 100 Unicode code points; one or more parts
 * made of Unicode letters and combining marks, each part starting with a letter, separated by
 * exactly one space, hyphen, ASCII apostrophe or U+2019. Digits, symbols, emoji, control and format
 * characters are rejected. The same rule is implemented in TypeScript and both are tested against
 * {@code contracts/validation/person-name-vectors.json}.
 *
 * @param value the NFC-normalised name
 */
public record PersonName(String value) {

  /** Minimum length in Unicode code points. */
  public static final int MIN_CODE_POINTS = 2;

  /** Maximum length in Unicode code points. */
  public static final int MAX_CODE_POINTS = 100;

  private static final int RIGHT_SINGLE_QUOTATION_MARK = 0x2019;

  /** Why a candidate name was rejected. */
  public enum Rejection {
    /** Leading or trailing whitespace. */
    WHITESPACE,
    /** Fewer than 2 or more than 100 code points. */
    LENGTH,
    /** A character or separator sequence outside the rule. */
    CHARACTERS
  }

  /**
   * Creates a name from input that has already passed {@link #check(String)}.
   *
   * @throws IllegalArgumentException if the value is not valid or not NFC-normalised
   */
  public PersonName {
    Objects.requireNonNull(value, "value");
    Optional<Rejection> rejection = check(value);
    if (rejection.isPresent()) {
      throw new IllegalArgumentException("invalid person name: " + rejection.get());
    }
    if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
      throw new IllegalArgumentException("person name must be NFC-normalised");
    }
  }

  /**
   * Normalises raw input to NFC and validates it.
   *
   * @param raw name as entered
   * @return the normalised name
   * @throws IllegalArgumentException with the {@link Rejection} reason if invalid
   */
  public static PersonName parse(String raw) {
    Objects.requireNonNull(raw, "raw");
    return new PersonName(Normalizer.normalize(raw, Normalizer.Form.NFC));
  }

  /**
   * Returns why the input is not a valid name after NFC normalisation, or empty if it is valid.
   *
   * @param raw name as entered
   * @return the rejection reason, if any
   */
  public static Optional<Rejection> check(String raw) {
    if (raw == null) {
      return Optional.of(Rejection.CHARACTERS);
    }
    String normalised = Normalizer.normalize(raw, Normalizer.Form.NFC);
    if (!normalised.equals(normalised.strip())) {
      return Optional.of(Rejection.WHITESPACE);
    }
    int codePoints = normalised.codePointCount(0, normalised.length());
    if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
      return Optional.of(Rejection.LENGTH);
    }
    return partsAreValid(normalised) ? Optional.empty() : Optional.of(Rejection.CHARACTERS);
  }

  /**
   * Single pass over code points (no regular expression, so no backtracking): each part starts with
   * a letter and continues with letters or combining marks; parts are joined by exactly one
   * separator; the name does not end with a separator.
   */
  private static boolean partsAreValid(String name) {
    boolean expectingLetter = true;
    int index = 0;
    while (index < name.length()) {
      int codePoint = name.codePointAt(index);
      index += Character.charCount(codePoint);
      if (expectingLetter) {
        if (!Character.isLetter(codePoint)) {
          return false;
        }
        expectingLetter = false;
      } else if (isSeparator(codePoint)) {
        expectingLetter = true;
      } else if (!Character.isLetter(codePoint) && !isCombiningMark(codePoint)) {
        return false;
      }
    }
    return !expectingLetter;
  }

  private static boolean isSeparator(int codePoint) {
    return codePoint == ' '
        || codePoint == '-'
        || codePoint == '\''
        || codePoint == RIGHT_SINGLE_QUOTATION_MARK;
  }

  private static boolean isCombiningMark(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }
}
