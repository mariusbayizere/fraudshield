package io.github.mariusbayizere.fraudshield.ingest.request;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import java.time.Instant;
import java.util.UUID;

/**
 * A validated {@code TransactionIngestRequest} (FR-01-02 plus ADR 0011 section 12).
 *
 * @param transactionId idempotency key
 * @param accountToken account token
 * @param counterpartyToken counterparty token
 * @param amount amount and currency
 * @param channel channel
 * @param merchantCategoryCode MCC
 * @param merchantName display name, or null
 * @param latitude latitude
 * @param longitude longitude
 * @param deviceToken device fingerprint token, or null
 * @param agentToken agent token, or null
 * @param counterpartyCountry ISO 3166-1 alpha-2, or null
 * @param transactionTimestamp payment time
 */
public record IngestRequest(
    UUID transactionId,
    String accountToken,
    String counterpartyToken,
    Money amount,
    Channel channel,
    String merchantCategoryCode,
    String merchantName,
    double latitude,
    double longitude,
    String deviceToken,
    String agentToken,
    String counterpartyCountry,
    Instant transactionTimestamp) {}
