import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { defaultParseSearch } from '@tanstack/react-router';
import type { ListAlertsData } from '../api/generated/types.gen';
import { CHANNELS } from '../design-system/tokens';
import {
  ALERT_STATUSES,
  ALERT_TIERS,
  alertSearch,
  DECIMAL_AMOUNT,
  TOKEN,
  type AlertSearch,
} from './alertSearch';

describe('alert feed filters in the URL (FR-04-09)', () => {
  it('keeps valid filters, under listAlerts parameter names', () => {
    const search = {
      tier: ['HIGH', 'ANOMALY'],
      channel: ['USSD', 'CARD'],
      outcome: ['PENDING'],
      transaction_id: '0b6f4e0e-7a52-4c8e-9a4b-0a7b1d2c3e4f',
      account_id: 'tok_ABCDEFGHIJKLMNOPQRSTUVWX',
      amount_min: '1000',
      amount_max: '25000.5',
      from: '2026-10-01T00:00:00Z',
      to: '2026-10-18T23:59:59Z',
      analyst_id: '4a1f0f3e-5c27-4a1e-9a56-0e0f0a1b2c3d',
      sort: 'expected_loss',
    };
    expect(alertSearch.parse(search)).toEqual(search);
  });

  it('drops what it does not recognise instead of failing the page', () => {
    expect(
      alertSearch.parse({
        tier: ['high'],
        channel: ['FAX'],
        outcome: [],
        transaction_id: '4821',
        account_id: '250788123456',
        amount_min: '-5',
        amount_max: '1e3',
        from: '2026-10-01T00:00:00+02:00',
        to: 'yesterday',
        analyst_id: 'me',
        sort: 'oldest',
      }),
    ).toEqual({});
    expect(alertSearch.parse({})).toEqual({});
  });

  it('takes lists, as listAlerts does, and drops a list that is empty or repeats a value', () => {
    expect(alertSearch.parse({ tier: 'HIGH', channel: 'USSD', outcome: 'PENDING' })).toEqual({});
    expect(alertSearch.parse({ tier: [], channel: [], outcome: [] })).toEqual({});
    expect(
      alertSearch.parse({
        tier: ['HIGH', 'HIGH'],
        channel: ['USSD', 'USSD'],
        outcome: ['PENDING', 'PENDING'],
      }),
    ).toEqual({});
  });

  it('reads amounts from a typed or pasted link, which the router parses as numbers', () => {
    expect(alertSearch.parse(defaultParseSearch('?amount_min=1000&amount_max=25000.5'))).toEqual({
      amount_min: '1000',
      amount_max: '25000.5',
    });
  });

  it('has no free-text search: listAlerts has no such parameter', () => {
    expect(alertSearch.parse({ q: '4821' })).toEqual({});
  });

  it('carries exactly the filters listAlerts takes, with their types', () => {
    type Filters = Omit<NonNullable<ListAlertsData['query']>, 'queue' | 'cursor' | 'limit'>;
    expectTypeOf<
      Required<{ [K in keyof AlertSearch]: NonNullable<AlertSearch[K]> }>
    >().toEqualTypeOf<Required<{ [K in keyof Filters]: NonNullable<Filters[K]> }>>();
  });

  it('offers exactly the values the OpenAPI contract defines', () => {
    const contract = readFileSync(
      join(import.meta.dirname, '../../../contracts/openapi/fraudshield-api.yaml'),
      'utf8',
    );
    // Anchored on the schema name so a path description cannot match.
    const enumOf = (schema: string) =>
      new RegExp(`\n    ${schema}:\n      type: string\n      enum: \\[([^\\]]+)\\]`)
        .exec(contract)?.[1]
        ?.split(',')
        .map((value) => value.trim());
    expect(enumOf('AlertTier')).toEqual([...ALERT_TIERS]);
    expect(enumOf('AlertStatus')).toEqual([...ALERT_STATUSES]);
    expect(new Set(enumOf('Channel'))).toEqual(new Set(CHANNELS));
    const patternOf = (schema: string) =>
      new RegExp(`\n    ${schema}:\n      type: string\n      pattern: "([^"]+)"`)
        .exec(contract)?.[1]
        ?.replaceAll('\\\\', '\\');
    expect(patternOf('Token')).toBe(TOKEN.source);
    expect(patternOf('DecimalAmount')).toBe(DECIMAL_AMOUNT.source);
  });
});
