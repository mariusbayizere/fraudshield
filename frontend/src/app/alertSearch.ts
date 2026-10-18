import * as z from 'zod/mini';
import type { AlertStatus, AlertTier } from '../api/generated/types.gen';
import { CHANNELS } from '../design-system/tokens';

/** The contract's AlertTier: the feed's tiers, which are not the risk tiers (ANOMALY, no LOW). */
export const ALERT_TIERS = ['HIGH', 'MEDIUM', 'ANOMALY'] as const satisfies readonly AlertTier[];

/** The contract's AlertStatus, which listAlerts filters on as `outcome`. */
export const ALERT_STATUSES = [
  'PENDING',
  'IN_REVIEW',
  'CONFIRMED_FRAUD',
  'MARKED_LEGITIMATE',
  'AUTO_RELEASED',
  'ESCALATED',
  'OVERRIDDEN',
] as const satisfies readonly AlertStatus[];

export const ALERT_SORTS = ['priority', 'expected_loss', 'newest'] as const;

// The contract's Token and DecimalAmount patterns; a test compares them with the contract.
export const TOKEN = /^tok_[A-Za-z0-9]{24,64}$/;
export const DECIMAL_AMOUNT = /^(0|[1-9][0-9]{0,13})(\.[0-9]{1,4})?$/;

const distinct = (list: readonly string[]) => new Set(list).size === list.length;

// Below 1e11 an amount with at most four decimals has at most 15 significant digits, which a
// double carries exactly, so it reads back as the text that was typed.
const EXACT_BELOW = 1e11;

/**
 * An amount, which the contract carries as a decimal string. The router JSON-parses each search
 * value, so a typed or pasted `?amount_min=1000` arrives as a number and is read back as text.
 * A typed number too large to have kept its digits is dropped rather than changed; a quoted
 * amount, as the console writes it, is taken at any size the contract allows.
 */
const amount = z.catch(
  z.optional(
    z.pipe(
      z.pipe(
        z.union([z.string(), z.number().check(z.refine((n) => Math.abs(n) < EXACT_BELOW))]),
        z.transform((value: string | number) => String(value)),
      ),
      z.string().check(z.regex(DECIMAL_AMOUNT)),
    ),
  ),
  undefined,
);

/**
 * The alert feed's filters, carried in the URL so a filtered view can be shared and restored
 * (FR-04-09). Each one is a listAlerts query parameter with the contract's name and type: the
 * screen filters by what the API can filter by, and nothing else. The list filters take one or
 * more values, none repeated (`uniqueItems`). `queue` is not here because the screen, not the
 * URL, chooses it, nor are `cursor` and `limit`, which page the feed rather than filter it.
 * Anything unrecognised is dropped rather than failing the page: a stale or hand-edited link
 * opens the unfiltered feed. zod/mini keeps the initial bundle small (D-39).
 */
export const alertSearch = z.object({
  tier: z.catch(
    z.optional(z.array(z.enum(ALERT_TIERS)).check(z.minLength(1), z.refine(distinct))),
    undefined,
  ),
  channel: z.catch(
    z.optional(z.array(z.enum(CHANNELS)).check(z.minLength(1), z.refine(distinct))),
    undefined,
  ),
  outcome: z.catch(
    z.optional(z.array(z.enum(ALERT_STATUSES)).check(z.minLength(1), z.refine(distinct))),
    undefined,
  ),
  transaction_id: z.catch(z.optional(z.uuid()), undefined),
  account_id: z.catch(z.optional(z.string().check(z.regex(TOKEN))), undefined),
  amount_min: amount,
  amount_max: amount,
  from: z.catch(z.optional(z.iso.datetime()), undefined),
  to: z.catch(z.optional(z.iso.datetime()), undefined),
  analyst_id: z.catch(z.optional(z.uuid()), undefined),
  sort: z.catch(z.optional(z.enum(ALERT_SORTS)), undefined),
});
export type AlertSearch = z.infer<typeof alertSearch>;
