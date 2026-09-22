import Button from '@mui/material/Button';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { EmptyState, ErrorState, PermissionDeniedState, StaleNotice } from './ScreenStates';

const meta = {
  title: 'Design system/Screen states',
  component: EmptyState,
  args: {
    title: 'No alerts need review',
    description: 'New HIGH and MEDIUM alerts appear here as they arrive.',
  },
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};
export const EmptyWithAction: Story = {
  args: {
    title: 'No alerts match these filters',
    description: 'Nothing in the last 24 hours matches HIGH on USSD.',
    action: <Button variant="outlined">Clear filters</Button>,
  },
};
export const LoadFailed: Story = {
  render: () => (
    <ErrorState
      message="The alert feed could not be loaded."
      correlationId="3f2a9c1e-7b4d-4e0a-9f6b-2c8d1e5a7b90"
      onRetry={() => undefined}
    />
  ),
};
export const ErrorWithoutRetry: Story = {
  render: () => <ErrorState message="This report is no longer available." />,
};
export const Stale: Story = {
  render: () => <StaleNotice lastUpdated={new Date('2026-09-22T12:02:00Z')} />,
};
export const PermissionDenied: Story = { render: () => <PermissionDeniedState /> };
export const LoadFailedDark: Story = { ...LoadFailed, globals: { theme: 'dark' } };
