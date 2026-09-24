// Journey 1 of TEST-12: sign in, the feed loads, a HIGH alert pulses, the drawer opens, the SHAP
// chart renders inside its budget, CONFIRM FRAUD with a comment, the alert leaves the feed.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010). On the
// current console branch every route renders a placeholder, so this spec describes the contract it
// will be run against, not a passing test.
//
// Requirements: FR-04-01 and FR-07-03 (sign-in), FR-04-02 and NFR-PERF-07 (feed), D-34 (the pulse
// and its removal), FR-04-03 (card elements), FR-04-05 and NFR-PERF-06 (SHAP), FR-04-04 and D-44
// (decide, with the undo grace), TEST-06 (what the decision writes server-side).
import { expect, test } from '@playwright/test';

import { recordTiming, seriousViolations, signIn } from './helpers';

// The class a pulsing HIGH card carries. D-34 requires the pulse to stop after three cycles, and
// names this journey as the thing that asserts it arrives and leaves.
const PULSE_CLASS = 'fs-pulse';

test.describe('[TEST-12] journey 1 — confirm fraud on a HIGH alert', () => {
  test('[FR-04-04] a HIGH alert is investigated and confirmed, and leaves the feed', async ({
    page,
  }, testInfo) => {
    await signIn(page, 'ANALYST');

    // --- the feed (FR-04-02, NFR-PERF-07) -------------------------------------------------
    const feedStarted = Date.now();
    const feedResponse = page.waitForResponse(
      (response) => response.url().includes('/api/v1/alerts') && response.request().method() === 'GET',
    );
    await page.goto('/alerts');
    await feedResponse;
    const feed = page.getByRole('main');
    await expect(feed.getByRole('heading', { level: 1, name: 'Alerts' })).toBeVisible();
    const cards = feed.getByRole('article');
    await expect(cards.first()).toBeVisible();
    await recordTiming(testInfo, 'NFR-PERF-07 alert feed interactive', 1000, Date.now() - feedStarted);

    // --- the HIGH card and its pulse (D-34, FR-04-03) -----------------------------------------
    // HIGH is pinned at the top of the feed, so the first card is the one under test.
    const highCard = cards.first();
    await expect(highCard).toHaveClass(new RegExp(PULSE_CLASS));
    // The pulse settles after three cycles; the card keeps its badge and stays identifiable.
    await expect(highCard).not.toHaveClass(new RegExp(PULSE_CLASS), { timeout: 10_000 });
    await expect(highCard.getByText('HIGH RISK')).toBeVisible();
    await expect(highCard.getByRole('meter', { name: 'Fraud score' })).toBeVisible();

    // --- the drawer and the explanation (FR-04-05, NFR-PERF-06) -------------------------------
    const explanation = page.waitForResponse((response) =>
      response.url().includes('/explanation'),
    );
    const drawerStarted = Date.now();
    await highCard.click();
    await explanation;
    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible();
    await drawer.getByRole('tab', { name: 'SHAP Explanation' }).click();
    // The chart is interactive when its bars are in the accessibility tree, not when the request
    // returns: the budget in NFR-PERF-06 is about what the analyst can use.
    await expect(drawer.getByRole('img', { name: /SHAP/i }).or(drawer.getByRole('figure'))).toBeVisible();
    await recordTiming(testInfo, 'NFR-PERF-06 SHAP chart interactive', 500, Date.now() - drawerStarted);

    // --- the decision (FR-04-04, D-44) --------------------------------------------------------
    const confirm = drawer.getByRole('button', { name: 'Confirm fraud' });
    await expect(confirm).toBeDisabled(); // no comment yet: the minimum is ten characters
    await drawer.getByRole('textbox', { name: /comment/i }).fill('Confirmed by the M10 journey');
    await expect(confirm).toBeEnabled();

    const decision = page.waitForResponse(
      (response) =>
        response.url().includes('/decisions') && response.request().method() === 'POST',
    );
    await confirm.click();
    const decided = await decision;
    // 202 with a PENDING_COMMIT state and an undo window is the contract; a 200 here would mean
    // the side effects were published before the grace period, which D-44 forbids.
    expect(decided.status()).toBe(202);
    const body = (await decided.json()) as { state?: string; undo_until?: string };
    expect(body.state).toBe('PENDING_COMMIT');
    expect(body.undo_until).toBeTruthy();

    // The undo affordance is offered, and the alert leaves the feed either way.
    await expect(page.getByRole('button', { name: /undo/i })).toBeVisible();
    await expect(highCard).toBeHidden();

    expect(await seriousViolations(page)).toEqual([]);
  });
});
