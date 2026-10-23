// Journey 3 of TEST-12: an analyst escalates an alert to a Risk Officer.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).
//
// Requirements: FR-04-10 (escalation with a mandatory reason, the original linked, the target role
// notified). Contract: POST /api/v1/alerts/{alert_id}/escalations takes target_role, a reason of
// at least ten characters and the alert_version the analyst saw; a stale version is refused with
// 409, and a target that does not rank above the caller with 422.
//
// The journey is run twice over: the analyst escalates, and then the Risk Officer signs in and
// finds the entry in their escalated queue. An escalation that writes a record nobody receives is
// the failure mode worth testing, and it is invisible from the sending side alone.
import { expect, test } from '@playwright/test';

import { seriousViolations, signIn } from './helpers';

test.describe('[TEST-12] journey 3 — escalation to a Risk Officer', () => {
  test('[FR-04-10] the escalation is accepted and reaches the risk officer queue', async ({
    browser,
  }) => {
    const analystContext = await browser.newContext();
    const analyst = await analystContext.newPage();
    await signIn(analyst, 'ANALYST');
    await analyst.goto('/alerts');

    const card = analyst.getByRole('article').first();
    const alertHref = await card.getAttribute('data-alert-id');
    await card.click();
    const drawer = analyst.getByRole('dialog');
    await drawer.getByRole('button', { name: /escalate/i }).click();

    // The reason is mandatory and has a minimum length; the control stays disabled until it is met.
    const submit = drawer.getByRole('button', { name: /^escalate$/i });
    await drawer.getByRole('combobox', { name: /target role/i }).selectOption('RISK_OFFICER');
    await expect(submit).toBeDisabled();
    await drawer.getByRole('textbox', { name: /reason/i }).fill('Structuring pattern across agents');
    await expect(submit).toBeEnabled();

    const escalation = analyst.waitForResponse(
      (response) =>
        response.url().includes('/escalations') && response.request().method() === 'POST',
    );
    await submit.click();
    const created = await escalation;
    expect(created.status()).toBe(201);

    expect(await seriousViolations(analyst)).toEqual([]);
    await analystContext.close();

    // --- the receiving side -------------------------------------------------------------------
    const officerContext = await browser.newContext();
    const officer = await officerContext.newPage();
    await signIn(officer, 'RISK_OFFICER');
    await officer.goto('/escalations');
    const queue = officer.getByRole('main');
    await expect(queue.getByRole('heading', { level: 1, name: 'Escalations' })).toBeVisible();
    // The escalated entry is the one the analyst raised, linked to the original alert.
    const entry = alertHref
      ? queue.getByRole('article').filter({ has: officer.locator(`[data-alert-id="${alertHref}"]`) })
      : queue.getByRole('article');
    await expect(entry.first()).toBeVisible();
    await expect(officer.getByText('Structuring pattern across agents')).toBeVisible();

    expect(await seriousViolations(officer)).toEqual([]);
    await officerContext.close();
  });
});
