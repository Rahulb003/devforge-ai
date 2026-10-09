import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';

import { expect, test } from './fixtures';
import { navTo, signIn, signUp, uniqueAccount } from './support';

/**
 * Automated accessibility checks on every main page, against WCAG 2.1 A and AA.
 *
 * Fails on serious and critical violations - the ones that stop someone using the page with a
 * screen reader or keyboard, or reading it at all. axe finds roughly a third of real issues; the
 * rest still need a person, so this is a floor, not a certificate.
 */
async function expectAccessible(page: Page, label: string) {
  // Let data load, so the check sees the page people use rather than its skeleton.
  await page.waitForLoadState('networkidle');
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map(
      (v) =>
        `${label}: ${v.id} (${v.impact}) - ${v.help}\n    ${v.nodes
          .slice(0, 3)
          .map((n) => n.target.join(' '))
          .join('\n    ')}`,
    );
  // Soft, so one run reports every page's problems rather than stopping at the first.
  expect.soft(blocking, blocking.join('\n')).toEqual([]);
}

/** Picks the theme before the app loads, the way a returning visitor's saved choice does. */
async function useTheme(page: Page, theme: 'dark' | 'light') {
  await page.addInitScript((value) => localStorage.setItem('devforge-theme', value), theme);
}

/** The light theme must actually be light: the toggle once changed only its own label. */
async function expectBackground(page: Page, theme: 'dark' | 'light') {
  const luminance = await page.evaluate(() => {
    const [r, g, b] = getComputedStyle(document.body)
      .backgroundColor.match(/[\d.]+/g)!
      .slice(0, 3)
      .map(Number);
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  });
  if (theme === 'light') expect(luminance).toBeGreaterThan(0.8);
  else expect(luminance).toBeLessThan(0.2);
}

for (const theme of ['dark', 'light'] as const) {
  test.describe(`Accessibility (${theme} theme)`, () => {
    test.beforeEach(async ({ page }) => {
      await useTheme(page, theme);
    });

    test('signed-out pages', async ({ page }) => {
      await page.goto('/login');
      await expectBackground(page, theme);
      await expectAccessible(page, 'login');
      await page.goto('/signup');
      await expectAccessible(page, 'signup');
      await page.goto('/forgot-password');
      await expectAccessible(page, 'forgot password');
    });

    test('the signed-in application', async ({ page }) => {
      const account = uniqueAccount();
      await signUp(page, account);
      await signIn(page, account.email, account.password);
      await expectAccessible(page, 'dashboard');

      const orgName = `A11y Org ${Date.now()}`;
      await navTo(page, 'Organizations');
      await page.getByRole('button', { name: 'Create organization' }).click();
      await page.getByLabel('Name').fill(orgName);
      await page.getByRole('button', { name: 'Create', exact: true }).click();
      await expectAccessible(page, 'organizations');
      await page
        .getByRole('main')
        .getByRole('link', { name: new RegExp(orgName, 'i') })
        .click();
      await page.getByRole('button', { name: 'Create project' }).click();
      await page.getByLabel('Name').fill('Accessible Project');
      await page.getByRole('button', { name: 'Create', exact: true }).click();
      await expectAccessible(page, 'organization');
      await page
        .getByRole('main')
        .getByRole('link', { name: /Accessible Project/i })
        .click();

      await page.getByRole('button', { name: 'New task' }).click();
      await page.getByLabel('Title').fill('Check the contrast');
      await page.getByRole('button', { name: 'Create', exact: true }).click();
      await expect(page.getByRole('button', { name: /Check the contrast/ })).toBeVisible();
      await expectAccessible(page, 'board');
      await page.getByRole('button', { name: /Check the contrast/ }).click();
      await expectAccessible(page, 'task drawer');
      await page.keyboard.press('Escape');

      await page.getByRole('link', { name: 'Chat' }).click();
      await expectAccessible(page, 'chat');
      await page.goBack();
      await page.getByRole('link', { name: 'Activity' }).click();
      await expectAccessible(page, 'activity');
      await page.goBack();

      await page.getByRole('link', { name: 'Repositories' }).click();
      await page.getByRole('button', { name: 'New repository' }).click();
      await page.getByLabel('Name').fill('a11y-repo');
      await page.getByRole('button', { name: 'Create', exact: true }).click();
      await expectAccessible(page, 'repositories');
      await page.getByRole('link', { name: /a11y-repo/ }).click();
      await page.getByRole('button', { name: /add a file/i }).click();
      await page.getByLabel('Path').fill('README.md');
      await page.getByLabel('Content').fill('# readme');
      await page.getByLabel('Commit message').fill('Add readme');
      await page.getByRole('button', { name: 'Commit' }).click();
      await expect(page.getByRole('button', { name: /^README\.md file/ })).toBeVisible();
      await expectAccessible(page, 'repository');
      await page.getByRole('button', { name: /^README\.md file/ }).click();
      await expectAccessible(page, 'file');
      await page.getByRole('link', { name: 'Pull requests' }).click();
      await expectAccessible(page, 'pull requests');

      await navTo(page, 'Notifications');
      await expectAccessible(page, 'notifications');
      await navTo(page, 'Settings');
      await expectAccessible(page, 'settings');
    });
  });
}
