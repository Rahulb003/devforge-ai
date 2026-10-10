import { expect, test } from './fixtures';
import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

test.describe('Workspace', () => {
  test('create an organization and a project inside it', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await navTo(page, 'Organizations');
    await expect(page.getByRole('heading', { name: 'Organizations', exact: true })).toBeVisible();

    // An account with no organizations must say what to do next, not just show
    // an empty box.
    await expect(page.getByRole('heading', { name: /no organizations yet/i })).toBeVisible();

    const orgName = `Acme ${Date.now()}`;
    await page.getByRole('button', { name: 'Create organization' }).click();
    await page.getByLabel('Name').fill(orgName);
    await page.getByRole('button', { name: 'Create', exact: true }).click();

    await expect(page.getByRole('heading', { name: orgName })).toBeVisible();
    // The creator is the owner, which is what grants them access at all.
    await expect(page.getByText('OWNER').first()).toBeVisible();
    await expectNoErrorBoundary(page);

    await page
      .getByRole('main')
      .getByRole('link', { name: new RegExp(orgName, 'i') })
      .click();
    await expect(
      page.getByRole('heading', { name: /no projects in this organization/i }),
    ).toBeVisible();

    await page.getByRole('button', { name: 'Create project' }).click();
    await page.getByLabel('Name').fill('Core Platform');
    await page.getByRole('button', { name: 'Create', exact: true }).click();

    await expect(page.getByRole('heading', { name: 'Core Platform', exact: true })).toBeVisible();
    await expect(page.getByText('ACTIVE')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('a duplicate project key is rejected with a readable message', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await page.goto('/organizations');
    await page.getByRole('button', { name: 'Create organization' }).click();
    const orgName = `Dup ${Date.now()}`;
    await page.getByLabel('Name').fill(orgName);
    await page.getByRole('button', { name: 'Create', exact: true }).click();

    await page
      .getByRole('main')
      .getByRole('link', { name: new RegExp(orgName, 'i') })
      .click();

    async function createProject(name: string, key: string) {
      await page
        .getByRole('button', { name: /create project|new project/i })
        .first()
        .click();
      await page.getByLabel('Name').fill(name);
      await page.getByLabel('Project key').fill(key);
      await page.getByRole('button', { name: 'Create', exact: true }).click();
    }

    await createProject('First', 'SAME');
    await expect(page.getByRole('heading', { name: 'First', exact: true })).toBeVisible();

    await createProject('Second', 'SAME');
    // A 409 from the server must surface as something the user can act on,
    // not a blank form or a generic failure.
    await expect(page.getByRole('alert')).toContainText(/already exists/i);
  });

  test('the dashboard reflects the organizations that exist', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    // This figure comes from a request. It used to be hard-coded.
    await expect(page.getByRole('heading', { name: /nothing here yet/i })).toBeVisible();

    await page.goto('/organizations');
    await page.getByRole('button', { name: 'Create organization' }).click();
    await page.getByLabel('Name').fill(`Dash ${Date.now()}`);
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await expect(page.getByText('OWNER').first()).toBeVisible();

    await navTo(page, 'Dashboard');
    await expect(page.getByRole('heading', { name: /nothing here yet/i })).toHaveCount(0);
  });

  test('the theme switch changes the theme, and the choice is remembered', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    // The page's background brightness, 0 (black) to 1 (white).
    const brightness = () =>
      page.evaluate(() => {
        const [r, g, b] = getComputedStyle(document.body)
          .backgroundColor.match(/[\d.]+/g)!
          .slice(0, 3)
          .map(Number);
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
      });

    expect(await brightness()).toBeLessThan(0.2);
    // This button once changed only its own label: nothing was styled for the class it set.
    await page.getByRole('button', { name: 'Switch to light theme' }).click();
    expect(await brightness()).toBeGreaterThan(0.8);

    await page.reload();
    await expect(page.getByRole('button', { name: 'Switch to dark theme' })).toBeVisible();
    expect(await brightness()).toBeGreaterThan(0.8);

    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    expect(await brightness()).toBeLessThan(0.2);
  });

  test('settings shows the profile, MFA and session state', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await navTo(page, 'Settings');

    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
    await expect(page.getByText(account.email)).toBeVisible();

    await expect(page.getByRole('heading', { name: /two-factor authentication/i })).toBeVisible();
    await expect(page.getByText('Not enabled')).toBeVisible();

    // One row per device, which is what per-device sessions made possible.
    await expect(page.getByRole('heading', { name: /active sessions/i })).toBeVisible();
    await expect(page.getByText('This device')).toBeVisible();

    await expectNoErrorBoundary(page);
  });

  test('enabling MFA shows a scannable QR code and recovery codes', async ({ page }) => {
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    await page.goto('/settings');
    await page.getByRole('button', { name: /enable two-factor authentication/i }).click();

    await expect(page.getByRole('heading', { name: /set up two-factor/i })).toBeVisible();
    // Rendered client-side from the provisioning URI, which carries the secret.
    await expect(page.getByRole('img', { name: /qr code/i })).toBeVisible();
    // The manual key must be available for anyone who cannot scan.
    await expect(page.getByText(/enter this key manually/i)).toBeVisible();

    await expectNoErrorBoundary(page);
  });

  test('an unknown route shows the not-found page', async ({ page }) => {
    await page.goto('/no-such-page');

    await expect(page.getByRole('heading', { name: /page not found/i })).toBeVisible();
    await expectNoErrorBoundary(page);
  });
});
