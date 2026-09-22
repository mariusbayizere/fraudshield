import * as z from 'zod/mini';
import { CHANNELS, RISK_TIERS } from '../design-system/tokens';

/**
 * The alert feed's filters, carried in the URL so a filtered view can be shared and restored
 * (FR-04-09). Anything unrecognised is dropped rather than failing the page: a stale or
 * hand-edited link opens the unfiltered feed. zod/mini keeps the initial bundle small (D-39).
 */
export const alertSearch = z.object({
  tier: z.catch(z.optional(z.enum(RISK_TIERS)), undefined),
  channel: z.catch(z.optional(z.enum(CHANNELS)), undefined),
  q: z.catch(z.optional(z.string().check(z.trim(), z.maxLength(100))), undefined),
});
export type AlertSearch = z.infer<typeof alertSearch>;
