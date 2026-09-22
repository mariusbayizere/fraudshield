import { screen } from '@testing-library/react';
import { renderThemed } from '../../../test/render';
import { BLOCK_THRESHOLD, FLAG_THRESHOLD, tierOf } from '../../risk';
import { RISK_TIERS, tokens } from '../../tokens';
import { RiskBadge } from './RiskBadge';

describe('RiskBadge', () => {
  it.each([
    ['high', 'HIGH RISK', 'WarningRoundedIcon'],
    ['medium', 'REVIEW', 'ScheduleRoundedIcon'],
    ['low', 'APPROVED', 'CheckCircleOutlineRoundedIcon'],
  ] as const)('writes the %s tier as "%s" with its own icon', (tier, label, icon) => {
    renderThemed(<RiskBadge tier={tier} />);
    expect(screen.getByText(label)).toBeVisible();
    expect(screen.getByTestId(icon)).toHaveAttribute('aria-hidden', 'true');
  });

  it.each(RISK_TIERS)('draws the %s text in its D-33 text-safe colour', (tier) => {
    renderThemed(<RiskBadge tier={tier} />);
    const chip = screen.getByText(/HIGH RISK|REVIEW|APPROVED/).closest('.MuiChip-root');
    expect(chip).toHaveStyle({
      color: tokens.color.light.risk[tier].text,
      backgroundColor: tokens.color.light.risk[tier].bg,
    });
  });
});

describe('tierOf (D-02)', () => {
  it.each([
    [1, 'high'],
    [BLOCK_THRESHOLD, 'high'],
    [0.8499, 'medium'],
    [FLAG_THRESHOLD, 'medium'],
    [0.5999, 'low'],
    [0, 'low'],
  ] as const)('puts %d in %s', (score, tier) => {
    expect(tierOf(score)).toBe(tier);
  });

  it.each([-0.01, 1.01, Number.NaN, Number.POSITIVE_INFINITY])('refuses %d', (score) => {
    expect(() => tierOf(score)).toThrow(RangeError);
  });
});
