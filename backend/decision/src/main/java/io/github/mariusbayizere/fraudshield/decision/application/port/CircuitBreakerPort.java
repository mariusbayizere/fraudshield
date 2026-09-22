package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Rolling per-MCC counts and breaker state, shared by every API instance (FR-03-07). */
public interface CircuitBreakerPort {

  /**
   * Whether the MCC's breaker is open. Answered from a local cache refreshed by the monitor, so it
   * adds nothing to the hot path.
   *
   * @param institutionId institution
   * @param merchantCategoryCode MCC
   * @return true when open
   */
  boolean isOpen(UUID institutionId, String merchantCategoryCode);

  /**
   * Counts a scored transaction for the MCC's rolling window.
   *
   * @param institutionId institution
   * @param merchantCategoryCode MCC
   * @param at decision time
   * @param fraud whether it counts as fraud (auto-blocked HIGH, or analyst-confirmed fraud)
   */
  void count(UUID institutionId, String merchantCategoryCode, Instant at, boolean fraud);

  /**
   * Counts a later analyst confirmation of fraud (D-18 numerator) without a new transaction.
   *
   * @param institutionId institution
   * @param merchantCategoryCode MCC
   * @param at confirmation time
   */
  void countConfirmedFraud(UUID institutionId, String merchantCategoryCode, Instant at);

  /**
   * MCCs with activity in the window, for the monitor.
   *
   * @param now evaluation time
   * @return (institution, MCC) keys
   */
  Collection<Key> active(Instant now);

  /**
   * Counts over the window ending now.
   *
   * @param key institution and MCC
   * @param window window length in minutes
   * @param now window end
   * @return counts
   */
  MccCircuitBreaker.WindowCounts counts(Key key, int window, Instant now);

  /**
   * Current state.
   *
   * @param key institution and MCC
   * @return state
   */
  CircuitBreakerState state(Key key);

  /**
   * Stores a new state; returns false if another instance changed it first (compare-and-set).
   *
   * @param key institution and MCC
   * @param expected state read before evaluating
   * @param next new state
   * @return whether this call's state was stored
   */
  boolean compareAndSet(Key key, CircuitBreakerState expected, CircuitBreakerState next);

  /**
   * An institution's MCC.
   *
   * @param institutionId institution
   * @param merchantCategoryCode MCC
   */
  record Key(UUID institutionId, String merchantCategoryCode) {}
}
