package io.github.mariusbayizere.fraudshield.rules.dsl;

/** How a rule field compares. Boolean features are numbers 0 and 1, as in the feature vector. */
public enum FieldType {
  /** Compared numerically (counts, amounts, ratios, flags, ordinals). */
  NUMBER,
  /** Compared as an exact string (channel, currency, corridor class, tokens). */
  CATEGORY
}
