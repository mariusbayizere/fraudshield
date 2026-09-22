import Chip from '@mui/material/Chip';
import { useTranslation } from 'react-i18next';
import type { Channel } from '../../tokens';

export interface ChannelChipProps {
  channel: Channel;
  size?: 'small' | 'medium';
}

/**
 * A transaction's channel. White text sits on the channel's `chip` token, which D-33 darkens
 * where the SRS hue fails AA (USSD amber, AGENT_BANKING teal); the name is always written.
 */
export function ChannelChip({ channel, size = 'small' }: ChannelChipProps) {
  const { t } = useTranslation('designSystem');
  return (
    <Chip
      label={t(`channel.${channel}`)}
      size={size}
      sx={(theme) => ({
        bgcolor: theme.palette.channel[channel].chip,
        color: theme.palette.chipText,
        fontWeight: theme.typography.fontWeightMedium,
      })}
    />
  );
}
