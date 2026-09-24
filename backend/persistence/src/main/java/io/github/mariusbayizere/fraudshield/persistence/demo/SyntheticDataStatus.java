package io.github.mariusbayizere.fraudshield.persistence.demo;

/**
 * Whether the UI must show the synthetic-data banner, decided once at startup by {@link
 * SyntheticDataGuard} (ADR 0019, D-21). The API serves it as {@code GET /api/v1/environment}.
 *
 * @param syntheticData the {@code synthetic_data} flag
 */
public record SyntheticDataStatus(boolean syntheticData) {}
