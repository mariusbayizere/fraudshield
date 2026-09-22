import type { Meta, StoryObj } from '@storybook/react-vite';
import { RiskScoreGauge } from './RiskScoreGauge';

const meta = {
  title: 'Design system/RiskScoreGauge',
  component: RiskScoreGauge,
  args: { score: 0.93 },
} satisfies Meta<typeof RiskScoreGauge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const High: Story = {};
export const AtBlockThreshold: Story = { args: { score: 0.85 } };
export const JustBelowBlock: Story = { args: { score: 0.849 } };
export const Medium: Story = { args: { score: 0.72 } };
export const Low: Story = { args: { score: 0.12 } };
export const Large: Story = { args: { score: 0.93, size: 96 } };
export const HighDark: Story = { globals: { theme: 'dark' } };
export const MediumDark: Story = { args: { score: 0.72 }, globals: { theme: 'dark' } };
