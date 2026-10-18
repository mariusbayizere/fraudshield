import { isFiltered, toggled } from './FeedFilters';

describe('the feed filters (FR-04-09)', () => {
  it('adds a value to a list filter, removes it, and drops a list it empties', () => {
    expect(toggled(undefined, 'HIGH')).toEqual(['HIGH']);
    expect(toggled(['HIGH'], 'MEDIUM')).toEqual(['HIGH', 'MEDIUM']);
    expect(toggled(['HIGH', 'MEDIUM'], 'HIGH')).toEqual(['MEDIUM']);
    expect(toggled(['HIGH'], 'HIGH')).toBeUndefined();
  });

  it('counts every filter as narrowing the feed except the order', () => {
    expect(isFiltered({})).toBe(false);
    expect(isFiltered({ sort: 'newest' })).toBe(false);
    expect(isFiltered({ tier: undefined })).toBe(false);
    expect(isFiltered({ channel: ['USSD'] })).toBe(true);
    expect(isFiltered({ amount_min: '1000' })).toBe(true);
  });
});
