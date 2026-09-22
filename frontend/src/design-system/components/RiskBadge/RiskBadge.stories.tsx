import type { Meta, StoryObj } from '@storybook/react-vite';
import { RiskBadge } from './RiskBadge';

const meta = {
  title: 'Design system/RiskBadge',
  component: RiskBadge,
  args: { tier: 'high' },
} satisfies Meta<typeof RiskBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const High: Story = {};
export const Medium: Story = { args: { tier: 'medium' } };
export const Low: Story = { args: { tier: 'low' } };
export const Small: Story = { args: { size: 'small' } };
export const HighDark: Story = { globals: { theme: 'dark' } };
export const MediumDark: Story = { args: { tier: 'medium' }, globals: { theme: 'dark' } };
export const LowDark: Story = { args: { tier: 'low' }, globals: { theme: 'dark' } };
