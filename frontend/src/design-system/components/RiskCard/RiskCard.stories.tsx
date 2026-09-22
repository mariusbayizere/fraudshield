import Typography from '@mui/material/Typography';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { RiskCard } from './RiskCard';

const meta = {
  title: 'Design system/RiskCard',
  component: RiskCard,
  args: {
    tier: 'high',
    'aria-label': 'Alert for account ****4821',
    children: (
      <Typography variant="body2" sx={{ mt: 1 }}>
        12 transfers in 60 seconds, 8.3× this account&apos;s normal rate.
      </Typography>
    ),
  },
} satisfies Meta<typeof RiskCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const HighSettled: Story = {};
export const HighJustArrived: Story = { args: { justArrived: true } };
export const Medium: Story = { args: { tier: 'medium' } };
export const Low: Story = { args: { tier: 'low' } };
export const HighDark: Story = { globals: { theme: 'dark' } };
export const MediumDark: Story = { args: { tier: 'medium' }, globals: { theme: 'dark' } };
