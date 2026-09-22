import { screen } from '@testing-library/react';
import { renderThemed } from '../../../test/render';
import { formatMoney, Money } from './Money';

// Intl separates the code from the number with a no-break space, so it never wraps apart.
const NBSP = ' ';

describe('formatMoney', () => {
  it.each([
    [1_250_000, 'RWF', `RWF${NBSP}1,250,000`],
    [0, 'RWF', `RWF${NBSP}0`],
    [999.6, 'RWF', `RWF${NBSP}1,000`],
    [1234.5, 'USD', `USD${NBSP}1,234.50`],
  ])('writes %d %s as %s', (amount, currency, text) => {
    expect(formatMoney(amount, currency)).toBe(text);
  });
});

describe('Money', () => {
  it('renders in tabular numerals', () => {
    renderThemed(<Money amount={1_250_000} />);
    // Testing Library normalises the no-break space to a space before matching.
    expect(screen.getByText('RWF 1,250,000')).toHaveStyle({
      fontVariantNumeric: 'tabular-nums',
    });
  });
});
