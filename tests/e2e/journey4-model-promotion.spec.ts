// Journey 4 of TEST-12: an administrator promotes a new model version.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).
//
// Requirements: FR-06-03 (list versions with their metrics, promote, roll back, toggle shadow
// mode) and D-11, which is the part that matters here: promotion is refused until the shadow
// window, the shadow volume, the AUC delta and the score-distribution drift all pass, and the
// console must say which condition blocks it rather than disabling a button silently.
//
// The gate's own inputs are M10 measurements (ML-GATE-13 is carried to this milestone), so the
// campaign runs this journey twice: once while the gate refuses, which is the state the system is
// in at the start of the campaign, and once after a shadow window that satisfies it.
import { expect, test } from '@playwright/test';

import { recordTiming, seriousViolations, signIn } from './helpers';

test.describe('[TEST-12] journey 4 — model promotion', () => {
  test('[FR-06-03] the promotion gate states its reason, and a passing version promotes', async ({
    page,
  }, testInfo) => {
    await signIn(page, 'ADMIN');
    await page.goto('/admin/models');
    await expect(page.getByRole('heading', { level: 1, name: 'Models' })).toBeVisible();

    const versions = page.getByRole('row');
    await expect(versions.first()).toBeVisible();
    const candidate = versions.filter({ hasText: /candidate|shadow/i }).first();
    const promote = candidate.getByRole('button', { name: /promote/i });

    // D-11: while the gate refuses, the control is unavailable **and** the reason is on screen.
    // A disabled button with no reason is the defect the resolution was written against.
    if (await promote.isDisabled()) {
      await expect(
        candidate.getByText(/shadow|labels|delta|distribution|insufficient/i).first(),
      ).toBeVisible();
      testInfo.annotations.push({
        type: 'gate',
        description: 'promotion refused by the D-11 gate; the campaign re-runs this after a shadow window',
      });
      expect(await seriousViolations(page)).toEqual([]);
      return;
    }

    const started = Date.now();
    const promotion = page.waitForResponse(
      (response) =>
        response.url().includes('/promotion') && response.request().method() === 'POST',
    );
    await promote.click();
    const accepted = await promotion;
    // 202: promotion is started, and takes effect inside the window FR-06-03 states.
    expect(accepted.status()).toBe(202);
    await expect(candidate.getByText(/production/i)).toBeVisible({ timeout: 60_000 });
    await recordTiming(testInfo, 'FR-06-03 promotion effective', 60_000, Date.now() - started);

    // The rollback path is part of the same requirement and is exercised immediately, because a
    // promotion that cannot be undone is not a promotion anyone should make at scale.
    const rollbackStarted = Date.now();
    const rollback = page.waitForResponse(
      (response) => response.url().includes('/models/rollback') && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /roll ?back/i }).click();
    expect((await rollback).status()).toBe(202);
    await recordTiming(testInfo, 'FR-06-03 rollback effective', 30_000, Date.now() - rollbackStarted);

    expect(await seriousViolations(page)).toEqual([]);
  });
});
