// Shared helpers for the five SRS journeys.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).
import { expect, type Page, type TestInfo } from '@playwright/test';

/** Roles the console distinguishes. ADMIN is separate from the analyst ladder (E.8). */
export type Role = 'ANALYST' | 'SENIOR_ANALYST' | 'RISK_OFFICER' | 'ADMIN';

/** Demo logins seeded by `make seed-demo`; passwords come from the environment, never from here. */
const DEMO_EMAIL: Record<Role, string> = {
  ANALYST: 'analyst.demo@example.com',
  SENIOR_ANALYST: 'senior.analyst.demo@example.com',
  RISK_OFFICER: 'risk.officer.a.demo@example.com',
  ADMIN: 'admin.demo@example.com',
};

/**
 * Sign in through the console's own login form.
 *
 * The access token lives in memory for the tab's lifetime and nowhere else, so a session cannot be
 * seeded through storage; the refresh cookie is what survives, and it is set by this form. The
 * password is read from the environment: `make seed-demo` writes one per role to the git-ignored
 * `.demo-credentials`, and a spec that hard-coded one would be a committed credential.
 */
export async function signIn(page: Page, role: Role): Promise<void> {
  const password = process.env[`FS_E2E_PASSWORD_${role}`];
  if (!password) {
    throw new Error(
      `set FS_E2E_PASSWORD_${role} from .demo-credentials (make seed-demo); ` +
        'the journeys sign in as a real staff user and never embed a password',
    );
  }
  await page.goto('/login');
  await page.getByRole('textbox', { name: 'Work email' }).fill(DEMO_EMAIL[role]);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
}

/**
 * Record a duration against a performance requirement.
 *
 * Every timing in these journeys (the SHAP chart, the alert feed, the OAuth round trip) is a
 * benchmark row: a number from a shared runner is not gate evidence (ADR 0010). So the duration is
 * always recorded as an annotation, and it is only asserted when the run says it is on the
 * dedicated machine. A green CI run therefore never claims a performance result.
 */
export async function recordTiming(
  testInfo: TestInfo,
  requirement: string,
  budgetMs: number,
  measuredMs: number,
): Promise<void> {
  testInfo.annotations.push({
    type: 'timing',
    description: `${requirement}: ${String(Math.round(measuredMs))} ms (budget ${String(budgetMs)} ms)`,
  });
  if (process.env['FS_E2E_ASSERT_TIMING'] === '1') {
    expect(measuredMs, `${requirement} budget`).toBeLessThan(budgetMs);
  }
}

/**
 * Critical and serious Axe violations on the current page.
 *
 * The M8 gate is zero of them, and the campaign re-checks it on the deployed console rather than
 * on the mocked dev server, because a violation can be introduced by real data (an empty table, a
 * long merchant name, a banner) that the mocks never produce.
 */
export async function seriousViolations(page: Page): Promise<string[]> {
  await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
  const violations = await page.evaluate(async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const axe = (globalThis as any).axe;
    const results = await axe.run();
    return results.violations
      .filter((v: { impact: string }) => v.impact === 'critical' || v.impact === 'serious')
      .map((v: { impact: string; id: string; nodes: unknown[] }) => `${v.impact} ${v.id} (${String(v.nodes.length)})`);
  });
  return violations as string[];
}
