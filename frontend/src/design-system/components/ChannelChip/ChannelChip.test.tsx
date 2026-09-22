import { screen } from '@testing-library/react';
import en from '../../../i18n/locales/en/designSystem.json';
import { renderThemed } from '../../../test/render';
import { CHANNELS, tokens } from '../../tokens';
import { ChannelChip } from './ChannelChip';

describe('ChannelChip', () => {
  it.each(CHANNELS)('writes %s and draws it on its text-safe chip token', (channel) => {
    renderThemed(<ChannelChip channel={channel} />, 'dark');
    const chip = screen.getByText(en.channel[channel]).closest('.MuiChip-root');
    expect(chip).toHaveStyle({
      backgroundColor: tokens.color.dark.channel[channel].chip,
      color: tokens.color.dark.chipText,
    });
  });
});
