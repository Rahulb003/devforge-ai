import { expect, test, type Page } from '@playwright/test';

import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

/** Creates an organization, a project and a repository, then opens that repository. */
async function openRepository(page: Page, repoName: string) {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `Review Org ${Date.now()}`;
  await navTo(page, 'Organizations');
  await page.getByRole('button', { name: 'Create organization' }).click();
  await page.getByLabel('Name').fill(orgName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: new RegExp(orgName, 'i') })
    .click();

  await page.getByRole('button', { name: 'Create project' }).click();
  await page.getByLabel('Name').fill('Reviewed Project');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: /Reviewed Project/i })
    .click();

  await page.getByRole('link', { name: 'Repositories' }).click();
  await page.getByRole('button', { name: 'New repository' }).click();
  await page.getByLabel('Name').fill(repoName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.getByRole('link', { name: new RegExp(repoName) }).click();
  await expect(page.getByRole('heading', { name: repoName })).toBeVisible();
}

/** Commits one file through the browser. */
async function addFile(page: Page, path: string, content: string, message: string) {
  await page.getByRole('button', { name: /add a file/i }).click();
  await page.getByLabel('Path').fill(path);
  await page.getByLabel('Content').fill(content);
  await page.getByLabel('Commit message').fill(message);
  await page.getByRole('button', { name: 'Commit' }).click();
  await expect(page.getByRole('button', { name: /add a file/i })).toBeVisible();
}

test.describe('Code review', () => {
  test('a clean repository passes the quality gate', async ({ page }) => {
    await openRepository(page, 'clean-repo');
    await addFile(page, 'src/App.java', 'class App {\n  void run() {}\n}\n', 'Add App');

    await page.getByRole('link', { name: 'Review' }).click();
    await expect(page.getByRole('heading', { name: 'Code review' })).toBeVisible();
    await expect(page.getByText('This repository has not been reviewed')).toBeVisible();

    await page.getByRole('button', { name: /run a review/i }).click();

    await expect(page.getByText('Quality gate passed')).toBeVisible();
    await expect(page.getByText('No findings')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('a committed credential fails the gate and is never shown in full', async ({ page }) => {
    await openRepository(page, 'leaky-repo');
    // A syntactically valid but functionally worthless AWS key id.
    await addFile(
      page,
      'src/Config.java',
      'public class Config {\n  static final String KEY = "AKIAIOSFODNN7EXAMPLX";\n}\n',
      'Add Config',
    );

    await page.getByRole('link', { name: 'Review' }).click();
    await page.getByRole('button', { name: /run a review/i }).click();

    await expect(page.getByText('Quality gate failed')).toBeVisible();
    await expect(page.getByText('1 blocker')).toBeVisible();
    await expect(page.getByText(/src\/Config\.java/)).toBeVisible();

    // The whole point of redaction: the finding describes the problem without reproducing it.
    await expect(page.getByText(/REDACTED/)).toBeVisible();
    await expect(page.getByText('AKIAIOSFODNN7EXAMPLX')).toHaveCount(0);
    await expectNoErrorBoundary(page);
  });

  test('dismissing a finding needs a reason and does not change the gate', async ({ page }) => {
    await openRepository(page, 'dismiss-repo');
    await addFile(
      page,
      'src/Config.java',
      'public class Config {\n  static final String KEY = "AKIAIOSFODNN7EXAMPLX";\n}\n',
      'Add Config',
    );

    await page.getByRole('link', { name: 'Review' }).click();
    await page.getByRole('button', { name: /run a review/i }).click();
    await expect(page.getByText('Quality gate failed')).toBeVisible();

    await page.getByRole('button', { name: /dismiss…/i }).click();
    // The server rejects an empty reason, so the button stays disabled rather than letting the
    // user discover that through an error.
    await expect(page.getByRole('button', { name: 'Dismiss', exact: true })).toBeDisabled();

    await page.getByLabel(/why is this acceptable/i).fill('Key already rotated');
    await page.getByRole('button', { name: 'Dismiss', exact: true }).click();

    // Exact, because the reason line below it also begins with "Dismissed:".
    await expect(page.getByText('Dismissed', { exact: true })).toBeVisible();
    await expect(page.getByText('Dismissed: Key already rotated')).toBeVisible();
    // The gate records what the analysis found; a dismissal must not rewrite it.
    await expect(page.getByText('Quality gate failed')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('an empty repository offers no review link, since there is nothing to analyse', async ({
    page,
  }) => {
    await openRepository(page, 'empty-repo');

    await expect(page.getByText('This repository is empty')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Review' })).toHaveCount(0);
    await expectNoErrorBoundary(page);
  });
});
