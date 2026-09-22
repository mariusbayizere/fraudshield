import { act, screen } from '@testing-library/react';
import { renderThemed } from '../../../test/render';
import { HIGH_PULSE_TOTAL_MS } from '../../theme';
import { tokens } from '../../tokens';
import { PULSE_CLASS, RiskCard } from './RiskCard';

function preferReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string): Partial<MediaQueryList> => ({
    matches: reduce && query.includes('prefers-reduced-motion: reduce'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

describe('RiskCard (D-34)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    preferReducedMotion(false);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('pulses a HIGH card that has just arrived for three cycles, then stops', () => {
    renderThemed(<RiskCard tier="high" justArrived aria-label="alert" />);
    const card = screen.getByRole('article', { name: 'alert' });
    expect(card).toHaveClass(PULSE_CLASS);
    act(() => {
      vi.advanceTimersByTime(HIGH_PULSE_TOTAL_MS - 1);
    });
    expect(card).toHaveClass(PULSE_CLASS);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(card).not.toHaveClass(PULSE_CLASS);
  });

  it('never pulses when reduced motion is requested', () => {
    preferReducedMotion(true);
    renderThemed(<RiskCard tier="high" justArrived aria-label="alert" />);
    expect(screen.getByRole('article')).not.toHaveClass(PULSE_CLASS);
  });

  it.each([
    ['a settled HIGH card', 'high', false],
    ['a MEDIUM card', 'medium', true],
    ['a LOW card', 'low', true],
  ] as const)('does not pulse %s', (_what, tier, justArrived) => {
    renderThemed(<RiskCard tier={tier} justArrived={justArrived} aria-label="alert" />);
    expect(screen.getByRole('article')).not.toHaveClass(PULSE_CLASS);
  });

  it('leads with the tier in words and a 3 px tier border', () => {
    renderThemed(<RiskCard tier="high" aria-label="alert" />);
    const card = screen.getByRole('article');
    expect(card.textContent).toMatch(/^HIGH RISK/);
    expect(card).toHaveStyle({
      borderLeftWidth: '3px',
      borderColor: tokens.color.light.risk.high.border,
    });
  });
});
