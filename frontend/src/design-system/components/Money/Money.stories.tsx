import type { Meta, StoryObj } from '@storybook/react-vite';
import { Money } from './Money';

const meta = {
  title: 'Design system/Money',
  component: Money,
  args: { amount: 1_250_000 },
} satisfies Meta<typeof Money>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Rwf: Story = {};
export const Zero: Story = { args: { amount: 0 } };
export const Usd: Story = { args: { amount: 1234.5, currency: 'USD' } };
