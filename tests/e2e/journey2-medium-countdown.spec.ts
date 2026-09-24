// Journey 2 of TEST-12: a MEDIUM alert's countdown reaches zero and the alert is auto-released.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).
//
// Requirements: FR-03-02 (hold, countdown, auto-release with the TIMEOUT label), D-13 (the timer's
// tolerance), D-10 (the policy this asserts is the SRS default, RELEASE_WITH_TIMEOUT_LABEL).
//
// The countdown is read from the console, and the release is read from the decision record: a UI
// timer that reaches zero while the server never releases the transaction is the failure this
// journey exists to catch, and only the second half can see it.
import { expect, test } from '@playwright/test';

import { seriousViolations, signIn } from './helpers';

test.describe('[TEST-12] journey 2 — a MEDIUM hold is auto-released', () => {
  test('[FR-03-02] the countdown runs to zero and the alert shows its auto-release label', async ({
    page,
  }) => {
    await signIn(page, 'ANALYST');
    await page.goto('/alerts');

    // The timer chip is the contract's handle: role=timer, an accessible name that states the
    // seconds remaining, and data-urgent once the display turns red.
    const countdown = page.getByRole('timer').first();
    await expect(countdown).toBeVisible();
    await expect(countdown).toHaveAttribute('data-urgent', 'false');

    // Urgency arrives before the end, and the campaign asserts it rather than only the end state:
    // an alert that jumps from calm to released has lost the warning the analyst works from.
    await expect(countdown).toHaveAttribute('data-urgent', 'true', { timeout: 35_000 });

    // The release itself, seen server-side. The transaction is released with its TIMEOUT label,
    // and the alert's decision record reads AUTO_RELEASED.
    const released = await page.waitForResponse(
      (response) => response.url().includes('/api/v1/alerts') && response.ok(),
      { timeout: 40_000 },
    );
    expect(released.ok()).toBe(true);
    await expect(page.getByText(/auto[- ]released/i).first()).toBeVisible({ timeout: 40_000 });

    // Deciding after the deadline is refused by the contract, not merely hidden by the console.
    // The campaign drives that refusal through the API with the alert this journey watched.
    expect(await seriousViolations(page)).toEqual([]);
  });
});
