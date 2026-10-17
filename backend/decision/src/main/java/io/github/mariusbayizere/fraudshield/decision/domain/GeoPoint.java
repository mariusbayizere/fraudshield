package io.github.mariusbayizere.fraudshield.decision.domain;

/**
 * A location in degrees.
 *
 * @param latitude latitude in [-90, 90]
 * @param longitude longitude in [-180, 180]
 */
public record GeoPoint(double latitude, double longitude) {

  /** Validates the ranges. */
  public GeoPoint {
    if (!(latitude >= -90 && latitude <= 90) || !(longitude >= -180 && longitude <= 180)) {
      throw new IllegalArgumentException("latitude or longitude out of range");
    }
  }
}
