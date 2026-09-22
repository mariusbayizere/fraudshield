import type { Meta, StoryObj } from '@storybook/react-vite';
import { SYSTEM_CONDITIONS, SystemBanners } from './SystemBanner';

const meta = {
  title: 'Design system/SystemBanners',
  component: SystemBanners,
  args: { conditions: ['ML_UNAVAILABLE'] },
} satisfies Meta<typeof SystemBanners>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MlUnavailable: Story = {};
export const RealtimePaused: Story = { args: { conditions: ['REALTIME_PAUSED'] } };
export const Everything: Story = { args: { conditions: SYSTEM_CONDITIONS } };
export const EverythingDark: Story = {
  args: { conditions: SYSTEM_CONDITIONS },
  globals: { theme: 'dark' },
};
export const Healthy: Story = { args: { conditions: [] } };
