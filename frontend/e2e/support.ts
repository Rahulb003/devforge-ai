import { expect, type Page } from '@playwright/test';

/**
 * A unique account per test run.
 *
 * The database persists between runs, so a fixed address would collide with
 * itself on the second run and fail for the wrong reason.
 */
export function uniqueAccount() {
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  return {
    firstName: 'E2E',
    lastName: 'User',
    username: `e2e${suffix}`,
    email: `e2e${suffix}@example.com`,
    password: 'E2e-Str0ng-Passw0rd!',
    organization: 'DevForge E2E',
  };
}

/** Fills and submits the signup form. */
export async function signUp(page: Page, account: ReturnType<typeof uniqueAccount>) {
  await page.goto('/signup');

  await page.getByLabel('First name').fill(account.firstName);
  await page.getByLabel('Last name').fill(account.lastName);
  await page.getByLabel('Username').fill(account.username);
  await page.getByLabel('Email').fill(account.email);
  await page.getByLabel('Organization').fill(account.organization);
  await page.getByLabel('Password', { exact: true }).fill(account.password);

  await page.getByRole('button', { name: 'Create account' }).click();

  // Verification is disabled in the standalone profile, so the account is
  // usable immediately and the confirmation says so.
  await expect(page.getByRole('heading', { name: 'Account created' })).toBeVisible();
}

/** Signs in and waits for the authenticated shell. */
export async function signIn(page: Page, identifier: string, password: string) {
  await page.goto('/login');

  await page.getByLabel('Username or email').fill(identifier);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('heading', { name: /welcome back/i })).toBeVisible();
}

/**
 * Asserts the error boundary is not showing.
 *
 * Called after every navigation because the boundary renders instead of the
 * page, so a broken screen would otherwise just look like a missing element.
 */
export async function expectNoErrorBoundary(page: Page) {
  await expect(page.getByRole('heading', { name: /something went wrong/i })).toHaveCount(0);
}

/**
 * Clicks a sidebar navigation link.
 *
 * Scoped to the nav landmark because the same words appear in page content —
 * an unscoped getByRole('link') matches both and fails Playwright strict mode.
 */
export async function navTo(page: Page, label: string) {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: label }).click();
}
