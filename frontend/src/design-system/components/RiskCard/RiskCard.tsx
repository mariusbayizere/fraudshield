import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import { keyframes } from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useEffect, useState, type ReactNode } from 'react';
import { HIGH_PULSE_TOTAL_MS } from '../../theme';
import { tokens, type RiskTier } from '../../tokens';
import { RiskBadge } from '../RiskBadge/RiskBadge';

/** The class a pulsing card carries; the Playwright journey asserts it arrives and leaves (D-34). */
export const PULSE_CLASS = 'fs-pulse';

const pulse = keyframes`
  0% { box-shadow: 0 0 0 0 var(--fs-pulse-colour); }
  70% { box-shadow: 0 0 0 8px transparent; }
  100% { box-shadow: 0 0 0 0 transparent; }
`;

export interface RiskCardProps {
  tier: RiskTier;
  /** True for an alert that has just arrived in the feed; only then may a HIGH card pulse. */
  justArrived?: boolean;
  /** What the card is about, e.g. "Alert for account ****4821"; an article needs a name. */
  'aria-label': string;
  children?: ReactNode;
}

/**
 * An alert or transaction card that leads with its tier (E.9: the tier is read first).
 *
 * D-34: a HIGH card that has just arrived pulses for three 1.2 s cycles, then settles to a static
 * 3 px border on its inline-start edge (left in LTR, right in RTL). With reduced motion requested it never pulses; the CSS media query stops the
 * animation too, in case the preference changes after the card mounts.
 */
export function RiskCard({
  tier,
  justArrived = false,
  'aria-label': label,
  children,
}: RiskCardProps) {
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)', { noSsr: true });
  const [pulsing, setPulsing] = useState(tier === 'high' && justArrived && !reducedMotion);

  useEffect(() => {
    if (!pulsing) return undefined;
    const id = setTimeout(() => {
      setPulsing(false);
    }, HIGH_PULSE_TOTAL_MS);
    return () => {
      clearTimeout(id);
    };
  }, [pulsing]);

  return (
    <Card
      component="article"
      variant="outlined"
      className={pulsing ? PULSE_CLASS : ''}
      aria-label={label}
      sx={(theme) => ({
        '--fs-pulse-colour': theme.palette.risk.high.border,
        bgcolor: theme.palette.risk[tier].bg,
        borderColor: theme.palette.risk[tier].border,
        borderInlineStartWidth: 3,
        [`&.${PULSE_CLASS}`]: {
          animation: `${pulse} ${String(tokens.motion.durationMs.pulseCycle)}ms ease-out ${String(tokens.motion.pulseCycles)}`,
        },
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      })}
    >
      <CardContent>
        <RiskBadge tier={tier} size="small" />
        {children}
      </CardContent>
    </Card>
  );
}
