import Chip from '@mui/material/Chip';
import type { Channel } from '../../tokens';

/** How each channel is written on screen (SRS 5.2 channel chips). */
export const CHANNEL_LABEL: Record<Channel, string> = {
  MOBILE_MONEY: 'Mobile money',
  CARD: 'Card',
  USSD: 'USSD',
  AGENT_BANKING: 'Agent banking',
  ONLINE: 'Online',
  BANK_TRANSFER: 'Bank transfer',
};

export interface ChannelChipProps {
  channel: Channel;
  size?: 'small' | 'medium';
}

/**
 * A transaction's channel. White text sits on the channel's `chip` token, which D-33 darkens
 * where the SRS hue fails AA (USSD amber, AGENT_BANKING teal); the name is always written.
 */
export function ChannelChip({ channel, size = 'small' }: ChannelChipProps) {
  return (
    <Chip
      label={CHANNEL_LABEL[channel]}
      size={size}
      sx={(theme) => ({
        bgcolor: theme.palette.channel[channel].chip,
        color: theme.palette.chipText,
        fontWeight: theme.typography.fontWeightMedium,
      })}
    />
  );
}
