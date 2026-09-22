import TimerOutlined from '@mui/icons-material/TimerOutlined';
import Chip from '@mui/material/Chip';
import { useEffect, useRef, useState } from 'react';

/** Below this many seconds the chip turns red (SRS 5.4). */
export const URGENT_BELOW_SECONDS = 10;
/** Below this many seconds the optional chime may sound (SRS 5.4); the caller decides. */
export const FINAL_BELOW_SECONDS = 5;

export function secondsLeft(deadlineMs: number, nowMs: number): number {
  return Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000));
}

export interface CountdownChipProps {
  /** When the MEDIUM review window closes, in epoch milliseconds. */
  deadlineMs: number;
  /**
   * Called once when fewer than five seconds remain. The chime itself (Web Audio, only after the
   * analyst has opted in) belongs to the feed, not to the chip.
   */
  onFinalSeconds?: () => void;
}

/**
 * A MEDIUM alert's review countdown (SRS 5.4), ticking each second. The seconds are written,
 * and the chip turns to the HIGH colours below ten; `role="timer"` keeps screen readers from
 * announcing every tick.
 */
export function CountdownChip({ deadlineMs, onFinalSeconds }: CountdownChipProps) {
  const [now, setNow] = useState(Date.now);
  const firedFor = useRef<number | null>(null);
  const remaining = secondsLeft(deadlineMs, now);

  useEffect(() => {
    const id = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => {
      clearInterval(id);
    };
  }, []);

  useEffect(() => {
    if (remaining < FINAL_BELOW_SECONDS && firedFor.current !== deadlineMs) {
      firedFor.current = deadlineMs;
      onFinalSeconds?.();
    }
  }, [remaining, deadlineMs, onFinalSeconds]);

  const tier = remaining < URGENT_BELOW_SECONDS ? 'high' : 'medium';
  return (
    <Chip
      role="timer"
      aria-label={`${String(remaining)} seconds left to review`}
      icon={<TimerOutlined aria-hidden />}
      label={`${String(remaining)} s`}
      size="small"
      variant="outlined"
      data-urgent={tier === 'high'}
      sx={(theme) => ({
        bgcolor: theme.palette.risk[tier].bg,
        borderColor: theme.palette.risk[tier].border,
        color: theme.palette.risk[tier].text,
        fontVariantNumeric: 'tabular-nums',
        '& .MuiChip-icon': { color: 'inherit' },
      })}
    />
  );
}
