import CheckCircleOutlineRounded from '@mui/icons-material/CheckCircleOutlineRounded';
import ScheduleRounded from '@mui/icons-material/ScheduleRounded';
import WarningRounded from '@mui/icons-material/WarningRounded';
import Chip from '@mui/material/Chip';
import { TIER_LABEL } from '../../risk';
import type { RiskTier } from '../../tokens';

const ICON: Record<RiskTier, typeof WarningRounded> = {
  high: WarningRounded,
  medium: ScheduleRounded,
  low: CheckCircleOutlineRounded,
};

export interface RiskBadgeProps {
  tier: RiskTier;
  size?: 'small' | 'medium';
}

/**
 * The risk tier as a badge: a distinct icon and the tier's words, so it reads without colour
 * (FR-04-12). Text is drawn in the tier's text-safe colour on its tinted background, the pair
 * D-33 measures, not white on the SRS badge hue, which fails AA for MEDIUM and LOW.
 */
export function RiskBadge({ tier, size = 'medium' }: RiskBadgeProps) {
  const Icon = ICON[tier];
  return (
    <Chip
      icon={<Icon aria-hidden />}
      label={TIER_LABEL[tier]}
      size={size}
      variant="outlined"
      sx={(theme) => ({
        bgcolor: theme.palette.risk[tier].bg,
        borderColor: theme.palette.risk[tier].border,
        color: theme.palette.risk[tier].text,
        fontWeight: theme.typography.fontWeightBold,
        '& .MuiChip-icon': { color: 'inherit' },
      })}
    />
  );
}
