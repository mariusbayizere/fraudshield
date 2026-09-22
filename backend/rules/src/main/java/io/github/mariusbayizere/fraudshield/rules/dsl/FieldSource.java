package io.github.mariusbayizere.fraudshield.rules.dsl;

/** Where a rule field's value comes from (E.6: raw request fields or the 44 features). */
public enum FieldSource {
  /** A field of the validated ingest request. */
  REQUEST,
  /** One of the 44 registered features in the scoring result. */
  FEATURE
}
