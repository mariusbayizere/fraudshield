import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { BLOCK_THRESHOLD, tierOf } from '../../risk';

/**
 * The whole percentage a score shows. It rounds down, so the number never reaches a band the
 * score has not: 0.849 shows 84, not a HIGH-looking 85 under a REVIEW tier. The epsilon keeps
 * binary floating point from showing 0.57 as 56.
 */
export function scorePercent(score: number): number {
  return Math.floor(score * 100 + 1e-9);
}

export interface RiskScoreGaugeProps {
  score: number;
  size?: number;
}

/**
 * The fraud score as a ring with the percentage in its centre (SRS 5.4), coloured by D-02's
 * bands. Assistive technology reads it as a meter with the tier in words; the ring colour is
 * never the only signal.
 */
export function RiskScoreGauge({ score, size = 48 }: RiskScoreGaugeProps) {
  const { t } = useTranslation('designSystem');
  const tier = tierOf(score);
  const percent = scorePercent(score);
  return (
    <Tooltip title={t('gauge.explanation', { block: BLOCK_THRESHOLD })}>
      <Box
        role="meter"
        aria-label={t('gauge.name')}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={t('gauge.valueText', { percent, tier: t(`tier.${tier}`) })}
        // The explanation tooltip must be reachable from the keyboard, not by hover alone
        // (WCAG 2.1.1, 1.4.13).
        tabIndex={0}
        sx={{ position: 'relative', display: 'inline-flex', width: size, height: size }}
      >
        <CircularProgress
          aria-hidden
          variant="determinate"
          value={100}
          size={size}
          thickness={4}
          sx={(theme) => ({ color: theme.palette.divider, position: 'absolute' })}
        />
        <CircularProgress
          aria-hidden
          variant="determinate"
          value={percent}
          size={size}
          thickness={4}
          sx={(theme) => ({ color: theme.palette.risk[tier].border })}
        />
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Typography variant="numeric" component="span" sx={{ fontSize: size / 4 }}>
            {percent}%
          </Typography>
        </Box>
      </Box>
    </Tooltip>
  );
}
