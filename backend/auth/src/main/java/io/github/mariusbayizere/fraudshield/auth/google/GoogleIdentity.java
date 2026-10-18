package io.github.mariusbayizere.fraudshield.auth.google;

/**
 * A verified Google identity (FR-07-03).
 *
 * @param subject Google {@code sub}
 * @param email verified email, lower case
 * @param givenName given name, or null
 * @param familyName family name, or null
 * @param picture avatar URL, or null
 */
public record GoogleIdentity(
    String subject, String email, String givenName, String familyName, String picture) {}
