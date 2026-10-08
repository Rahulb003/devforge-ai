import { expect, test } from '@playwright/test';

import { expectNoErrorBoundary, signIn, signUp, uniqueAccount } from './support';

test.describe('Authentication', () => {
  test('an anonymous visitor is sent to sign in', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('the session tokens are out of reach of page script', async ({ page, context }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    // What an injected script could reach: storage and document.cookie.
    const visible = await page.evaluate(() => ({
      storage: JSON.stringify({ ...localStorage, ...sessionStorage }),
      cookies: document.cookie,
    }));
    expect(visible.storage).not.toMatch(/eyJ/);
    expect(visible.cookies).not.toContain('DEVFORGE_');

    const cookies = await context.cookies();
    const access = cookies.find((c) => c.name === 'DEVFORGE_ACCESS_TOKEN');
    expect(access?.httpOnly).toBe(true);
    expect(cookies.find((c) => c.name === 'DEVFORGE_REFRESH_TOKEN')?.httpOnly).toBe(true);

    // A write without the app's header is what a forged cross-site request looks like.
    const forged = await page.evaluate(async () => {
      const response = await fetch('/api/v1/organizations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'forged', slug: 'forged-org' }),
      });
      return response.status;
    });
    expect(forged).toBe(403);

    // Refresh as the app does it: a new access cookie, and nothing in the body for script to take.
    const before = access?.value;
    const refreshed = await page.evaluate(async () => {
      const response = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
      });
      return { status: response.status, body: await response.text() };
    });
    expect(refreshed.status).toBe(200);
    expect(refreshed.body).not.toMatch(/eyJ/);
    const after = (await context.cookies()).find((c) => c.name === 'DEVFORGE_ACCESS_TOKEN');
    expect(after?.value).toBeTruthy();
    expect(after?.value).not.toBe(before);
    const me = await page.evaluate(async () => (await fetch('/api/v1/auth/me')).status);
    expect(me).toBe(200);
  });

  test('sign up, then sign in and reach the dashboard', async ({ page }) => {
    const account = uniqueAccount();

    await signUp(page, account);
    await signIn(page, account.email, account.password);

    // The exact journey that was broken: the dashboard is the first screen
    // after sign-in and the first that loads data. It rendered the error
    // boundary because no QueryClientProvider was mounted.
    await expect(page.getByRole('heading', { name: /welcome back, e2e/i })).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('the username works for sign-in as well as the email', async ({ page }) => {
    const account = uniqueAccount();

    await signUp(page, account);
    await signIn(page, account.username, account.password);

    await expectNoErrorBoundary(page);
  });

  test('a wrong password is refused with a visible message', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);

    await page.goto('/login');
    await page.getByLabel('Username or email').fill(account.email);
    await page.getByLabel('Password', { exact: true }).fill('definitely-not-the-password');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByRole('alert')).toContainText(/invalid credentials/i);
    // Still on the login page, not half-signed-in.
    await expect(page).toHaveURL(/\/login$/);
  });

  test('the password field has exactly one reveal control', async ({ page }) => {
    await page.goto('/login');

    const field = page.getByLabel('Password', { exact: true });
    await field.fill('something');

    // Browsers render their own reveal button inside the input, which appeared
    // alongside ours as a second eye icon. The native one is suppressed in CSS.
    await expect(page.getByRole('button', { name: /show password/i })).toHaveCount(1);

    await page.getByRole('button', { name: /show password/i }).click();
    await expect(field).toHaveAttribute('type', 'text');
    await expect(page.getByRole('button', { name: /hide password/i })).toHaveCount(1);
  });

  test('signing out returns to the login page and protects the app', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);

    // The session is gone server-side as well, so going back is not enough.
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('a session survives a page reload', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await page.reload();

    // RequireAuth waits for the store to restore before deciding; without that
    // a reload bounces a signed-in user to login.
    await expect(page.getByRole('heading', { name: /welcome back/i })).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('the forgot-password form does not disclose whether an account exists', async ({ page }) => {
    await page.goto('/forgot-password');

    await page.getByLabel('Email').fill('nobody-at-all@example.com');
    await page.getByRole('button', { name: 'Send reset link' }).click();

    // Same wording regardless, matching the server's deliberately identical
    // response for known and unknown addresses.
    await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
    await expect(page.getByText(/if an account exists/i)).toBeVisible();
  });
});
