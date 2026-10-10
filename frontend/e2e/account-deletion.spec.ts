import { expect, test } from './fixtures';
import { navTo, signIn, signUp, uniqueAccount } from './support';

test.describe('Account deletion', () => {
  async function startDeleting(
    page: import('@playwright/test').Page,
    username: string,
    password: string,
  ) {
    await navTo(page, 'Settings');
    await page.getByRole('button', { name: 'Delete my account' }).click();
    const form = page.getByRole('form', { name: 'Delete account' });
    await form.getByLabel(`Type your username (${username})`).fill(username);
    await form.getByLabel('Password').fill(password);
    await form.getByRole('button', { name: 'Delete account permanently' }).click();
  }

  test('deleting an account signs out, and its password no longer works', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await startDeleting(page, account.username, account.password);

    await expect(page.getByRole('status')).toHaveText('Your account has been deleted.');
    await page.getByLabel('Username or email').fill(account.email);
    await page.getByLabel('Password', { exact: true }).fill(account.password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test('the only owner of an organization is told which one, and keeps the account', async ({
    page,
  }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);
    const orgName = `Owned Org ${Date.now()}`;
    await navTo(page, 'Organizations');
    await page.getByRole('button', { name: 'Create organization' }).click();
    await page.getByLabel('Name').fill(orgName);
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await expect(
      page.getByRole('main').getByRole('link', { name: new RegExp(orgName, 'i') }),
    ).toBeVisible();

    await startDeleting(page, account.username, account.password);

    await expect(
      page.getByRole('form', { name: 'Delete account' }).getByRole('alert'),
    ).toContainText(orgName);
    await expect(page).toHaveURL(/\/settings/);
  });
});
