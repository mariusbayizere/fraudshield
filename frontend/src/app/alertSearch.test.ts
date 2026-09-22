import { alertSearch } from './alertSearch';

describe('alert feed filters in the URL (FR-04-09)', () => {
  it('keeps valid filters', () => {
    expect(alertSearch.parse({ tier: 'high', channel: 'USSD', q: ' 4821 ' })).toEqual({
      tier: 'high',
      channel: 'USSD',
      q: '4821',
    });
  });

  it('drops what it does not recognise instead of failing the page', () => {
    expect(alertSearch.parse({ tier: 'extreme', channel: 'FAX', q: 'x'.repeat(101) })).toEqual({});
    expect(alertSearch.parse({})).toEqual({});
  });
});
