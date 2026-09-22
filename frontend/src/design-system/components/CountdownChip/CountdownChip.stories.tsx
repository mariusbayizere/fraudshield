import type { Meta, StoryObj } from '@storybook/react-vite';
import { CountdownChip } from './CountdownChip';

// Deadlines are computed when a story renders, so each opens at the state its name describes.
const inSeconds = (s: number) => Date.now() + s * 1000;

const meta = {
  title: 'Design system/CountdownChip',
  component: CountdownChip,
  args: { deadlineMs: 0 },
} satisfies Meta<typeof CountdownChip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Plenty: Story = { render: () => <CountdownChip deadlineMs={inSeconds(45)} /> };
export const Urgent: Story = { render: () => <CountdownChip deadlineMs={inSeconds(8)} /> };
export const Expired: Story = { render: () => <CountdownChip deadlineMs={inSeconds(-1)} /> };
export const UrgentDark: Story = {
  render: () => <CountdownChip deadlineMs={inSeconds(8)} />,
  globals: { theme: 'dark' },
};
