import { screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { renderThemed } from '../../../test/render';
import {
  EmptyState,
  ErrorState,
  formatLastUpdated,
  PermissionDeniedState,
  StaleNotice,
} from './ScreenStates';

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

  it.each([
    ['2026-09-22T12:02:00Z', 'Last updated 14:02 CAT'],
    ['2026-01-15T22:30:00Z', 'Last updated 00:30 CAT'],
    ['2026-07-01T00:00:00Z', 'Last updated 02:00 CAT'],
  ])('writes %s as "%s", Kigali time', (iso, text) => {
    expect(formatLastUpdated(new Date(iso))).toBe(text);
  });

  it('announces a stale view politely', () => {
    renderThemed(<StaleNotice lastUpdated={new Date('2026-09-22T12:02:00Z')} />);
    expect(screen.getByRole('status')).toHaveTextContent('Last updated 14:02 CAT');
  });

  it('explains a permission denial without naming the hidden content', () => {
    renderThemed(<PermissionDeniedState />);
    expect(
      screen.getByRole('heading', { name: "You don't have access to this page" }),
    ).toBeVisible();
  });
});
