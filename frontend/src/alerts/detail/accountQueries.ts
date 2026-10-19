import { useQuery } from '@tanstack/react-query';
import {
  getAccountNetwork,
  getBehaviourProfile,
  listAccountTransactions,
} from '../../api/generated/sdk.gen';
import type {
  AccountNetwork,
  AccountTransactionPage,
  BehaviourProfile,
} from '../../api/generated/types.gen';
import { checked } from '../../api/validate';
import { ApiError, bodyOrThrow } from '../queries';

// As for the alert: production skips the schema check (ADR 0080 §3), so a 2xx that arrives as {}
// must fail here rather than reach a tab. These are the fields each tab cannot do without.
function isTransactionPage(body: unknown): body is AccountTransactionPage {
  const page = body as AccountTransactionPage | null;
  return typeof page === 'object' && page !== null && Array.isArray(page.items);
}

/** Typed as 24 hours; whether the server sent 24 is what is checked. */
function hasHours(hours: unknown): boolean {
  return Array.isArray(hours) && hours.length === 24;
}

function isBehaviourProfile(body: unknown): body is BehaviourProfile {
  const profile = body as BehaviourProfile | null;
  return (
    typeof profile === 'object' &&
    profile !== null &&
    hasHours(profile.hourly_counts) &&
    Array.isArray(profile.amount_histogram) &&
    Array.isArray(profile.channel_mix) &&
    Array.isArray(profile.comparison)
  );
}

function isNetwork(body: unknown): body is AccountNetwork {
  const network = body as AccountNetwork | null;
  return (
    typeof network === 'object' &&
    network !== null &&
    Array.isArray(network.nodes) &&
    Array.isArray(network.edges)
  );
}

/** FR-04-06 asks for the last 30: one page, which the contract caps at 30. */
export const HISTORY_LENGTH = 30;

export async function fetchHistory(accountToken: string): Promise<AccountTransactionPage> {
  const page = bodyOrThrow(
    await listAccountTransactions({
      path: { account_token: accountToken },
      query: { limit: HISTORY_LENGTH },
    }),
  );
  if (!isTransactionPage(page)) throw new ApiError(undefined);
  return checked('zAccountTransactionPage', page);
}

export async function fetchBehaviour(
  accountToken: string,
  transactionId: string,
): Promise<BehaviourProfile> {
  const profile = bodyOrThrow(
    await getBehaviourProfile({
      path: { account_token: accountToken },
      query: { transaction_id: transactionId },
    }),
  );
  if (!isBehaviourProfile(profile)) throw new ApiError(undefined);
  return checked('zBehaviourProfile', profile);
}

export async function fetchNetwork(accountToken: string): Promise<AccountNetwork> {
  const network = bodyOrThrow(await getAccountNetwork({ path: { account_token: accountToken } }));
  if (!isNetwork(network)) throw new ApiError(undefined);
  return checked('zAccountNetwork', network);
}

// Each is asked for only when its tab is opened: three requests an analyst may never need.

/** The account's last 30 transactions, most recent first as the server sends them (FR-04-06). */
export function useHistory(accountToken: string, enabled: boolean) {
  return useQuery({
    queryKey: ['account-history', accountToken],
    queryFn: () => fetchHistory(accountToken),
    enabled,
  });
}

/** The account's 90-day pattern and this transaction against it (FR-04-07). */
export function useBehaviour(accountToken: string, transactionId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['account-behaviour', accountToken, transactionId],
    queryFn: () => fetchBehaviour(accountToken, transactionId),
    enabled,
  });
}

/** The account's counterparties to the second degree, at most 50 accounts (FR-04-08). */
export function useNetwork(accountToken: string, enabled: boolean) {
  return useQuery({
    queryKey: ['account-network', accountToken],
    queryFn: () => fetchNetwork(accountToken),
    enabled,
  });
}
