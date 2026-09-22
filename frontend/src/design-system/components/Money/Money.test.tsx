import { screen } from '@testing-library/react';
import { renderThemed } from '../../../test/render';
import { Money } from './Money';

describe('Money', () => {
  it("writes the amount at the region's minor units, in tabular numerals", () => {
    // Country Z's currency has three minor units, unlike any real currency in the packs.
    renderThemed(<Money amount="1250000.5" currency="ZZZ" />);
    // Testing Library normalises the no-break space after the code to a space before matching.
    expect(screen.getByText('ZZZ 1,250,000.500')).toHaveStyle({
      fontVariantNumeric: 'tabular-nums',
    });
  });

  it('refuses an amount that is not a decimal string', () => {
    expect(() => renderThemed(<Money amount="1e6" currency="ZZZ" />)).toThrow(RangeError);
  });
});
