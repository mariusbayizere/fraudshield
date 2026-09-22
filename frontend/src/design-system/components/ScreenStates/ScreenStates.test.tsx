import { screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { renderThemed } from '../../../test/render';
import { EmptyState, ErrorState, PermissionDeniedState, StaleNotice } from './ScreenStates';

describe('screen states (E.9)', () => {
  it('says what an empty view means, under a heading, with its next action', () => {
    renderThemed(
      <EmptyState
        title="No alerts"
        description="New alerts appear here."
        action={<button type="button">Clear filters</button>}
      />,
    );
    expect(screen.getByRole('heading', { level: 2, name: 'No alerts' })).toBeVisible();
    expect(screen.getByText('New alerts appear here.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeVisible();
  });

  it('gives an error a plain message, a retry and the correlation ID', async () => {
    const onRetry = vi.fn();
    renderThemed(
      <ErrorState message="Could not load." correlationId="abc-123" onRetry={onRetry} />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load.Reference: abc-123');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('offers no retry when there is nothing to retry', () => {
    renderThemed(<ErrorState message="Gone." />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it("writes when a stale view was updated in the region's time, with its offset", () => {
    // Country Z is UTC+7: 12:02 UTC is 19:02 there.
    renderThemed(<StaleNotice lastUpdated={new Date('2026-09-22T12:02:00Z')} />);
    expect(screen.getByRole('status')).toHaveTextContent('Last updated 19:02 UTC+7');
  });

  it('crosses midnight into the next local day without a date slip in the label', () => {
    renderThemed(<StaleNotice lastUpdated={new Date('2026-01-15T22:30:00Z')} />);
    expect(screen.getByRole('status')).toHaveTextContent('Last updated 05:30 UTC+7');
  });

  it('explains a permission denial without naming the hidden content', () => {
    renderThemed(<PermissionDeniedState />);
    expect(
      screen.getByRole('heading', { name: "You don't have access to this page" }),
    ).toBeVisible();
  });
});
