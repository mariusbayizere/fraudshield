import type { RiskTier } from './tokens';

/** D-02's two operating points on the calibrated ensemble score. */
export const FLAG_THRESHOLD = 0.6;
export const BLOCK_THRESHOLD = 0.85;

/**
 * The tier a fraud score falls in: HIGH at or above the block threshold, MEDIUM at or above the
 * flag threshold, LOW below it (D-02; SRS 5.4's gauge bands).
 */
export function tierOf(score: number): RiskTier {
  if (!Number.isFinite(score) || score < 0 || score > 1) {
    throw new RangeError(`a fraud score is a probability in [0, 1], got ${String(score)}`);
  }
  if (score >= BLOCK_THRESHOLD) return 'high';
  if (score >= FLAG_THRESHOLD) return 'medium';
  return 'low';
}

/** The words on every tier badge (SRS 5.2). The tier is always written, never only coloured. */
export const TIER_LABEL: Record<RiskTier, string> = {
  high: 'HIGH RISK',
  medium: 'REVIEW',
  low: 'APPROVED',
};
