import Stack from '@mui/material/Stack';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { CHANNELS } from '../../tokens';
import { ChannelChip } from './ChannelChip';

const meta = {
  title: 'Design system/ChannelChip',
  component: ChannelChip,
  args: { channel: 'MOBILE_MONEY' },
} satisfies Meta<typeof ChannelChip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MobileMoney: Story = {};
export const Medium: Story = { args: { size: 'medium' } };

const allChannels = () => (
  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
    {CHANNELS.map((channel) => (
      <ChannelChip key={channel} channel={channel} />
    ))}
  </Stack>
);
export const AllChannels: Story = { render: allChannels };
export const AllChannelsDark: Story = { render: allChannels, globals: { theme: 'dark' } };
