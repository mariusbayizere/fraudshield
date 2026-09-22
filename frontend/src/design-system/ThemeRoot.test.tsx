import { screen } from '@testing-library/react';
import { renderThemed } from '../test/render';
import { RiskBadge } from './components/RiskBadge/RiskBadge';

/** MUI writes its own styles with physical properties; the RTL cache must mirror them. */
function iconMargins(): { start: string; end: string } {
  const style = getComputedStyle(screen.getByTestId('WarningRoundedIcon'));
  return {
    start: style.getPropertyValue('margin-left'),
    end: style.getPropertyValue('margin-right'),
  };
}

describe('ThemeRoot direction (ADR 0080)', () => {
  it('mirrors MUI’s physical margins in right-to-left', () => {
    const { unmount } = renderThemed(<RiskBadge tier="high" />, { direction: 'ltr' });
    const ltr = iconMargins();
    unmount();
    renderThemed(<RiskBadge tier="high" />, { direction: 'rtl' });
    const rtl = iconMargins();
    expect(ltr.start).not.toBe(ltr.end);
    expect(rtl).toEqual({ start: ltr.end, end: ltr.start });
    expect(document.documentElement.dir).toBe('rtl');
  });
});
