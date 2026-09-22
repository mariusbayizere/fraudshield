import { screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { renderThemed } from '../../../test/render';
import { testI18n } from '../../../test/i18n';
import { RiskScoreGauge, scorePercent } from './RiskScoreGauge';

describe('RiskScoreGauge', () => {
  it.each([
    [0.93, '93%, HIGH RISK'],
    [0.85, '85%, HIGH RISK'],
    [0.849, '84%, REVIEW'],
    [0.6, '60%, REVIEW'],
    [0.57, '57%, APPROVED'],
  ])('reads %d as "%s"', (score, text) => {
    renderThemed(<RiskScoreGauge score={score} />);
    const meter = screen.getByRole('meter', { name: 'Fraud score' });
    expect(meter).toHaveAttribute('aria-valuetext', text);
    expect(meter).toHaveTextContent(text.split(',')[0] ?? '');
  });

  it('never shows a percentage from a higher band than its tier', () => {
    for (let i = 0; i <= 1000; i += 1) {
      const score = i / 1000;
      const percent = scorePercent(score);
      expect(percent).toBeLessThanOrEqual(score * 100 + 1e-6);
      expect(percent).toBeGreaterThan(score * 100 - 1);
    }
  });

  it('is reachable from the keyboard and carries its explanation', async () => {
    renderThemed(<RiskScoreGauge score={0.9} />);
    await userEvent.tab();
    const meter = screen.getByRole('meter');
    expect(meter).toHaveFocus();
    // MUI opens a tooltip on focus only when the element matches :focus-visible, which jsdom
    // never does; the keyboard-opened tooltip is asserted in the Playwright journeys. Here the
    // pointer shows the same tooltip.
    await userEvent.hover(meter);
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'XGBoost + LightGBM ensemble. 0.85+ = HIGH risk auto-block threshold.',
    );
  });

  it('reads the tier and the threshold in the UI language', async () => {
    renderThemed(<RiskScoreGauge score={0.9} />, { i18n: testI18n('fr') });
    const meter = screen.getByRole('meter', { name: 'Score de fraude' });
    expect(meter).toHaveAttribute('aria-valuetext', '90 %, RISQUE ÉLEVÉ');
    await userEvent.hover(meter);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('0,85 et plus');
  });

  it('refuses a score outside [0, 1]', () => {
    expect(() => renderThemed(<RiskScoreGauge score={1.2} />)).toThrow(RangeError);
  });
});
