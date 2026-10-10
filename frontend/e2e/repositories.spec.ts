import type { Page } from '@playwright/test';

import { expect, test } from './fixtures';
import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

/**
 * Creates an organization and a project, then opens that project's repositories.
 *
 * Goes through the UI rather than seeding through the API on purpose: the point of these tests is
 * that the path a person actually takes works, including the links between pages.
 */
async function openRepositories(page: Page) {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `Git Org ${Date.now()}`;
  await navTo(page, 'Organizations');
  await page.getByRole('button', { name: 'Create organization' }).click();
  await page.getByLabel('Name').fill(orgName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: new RegExp(orgName, 'i') })
    .click();

  await page.getByRole('button', { name: 'Create project' }).click();
  await page.getByLabel('Name').fill('Git Project');
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  await page
    .getByRole('main')
    .getByRole('link', { name: /Git Project/i })
    .click();
  await expect(page.getByRole('heading', { name: 'Git Project', exact: true })).toBeVisible();

  // The board is the way in, which is the link being tested.
  await page.getByRole('link', { name: 'Repositories' }).click();
  await expect(page.getByRole('heading', { name: 'Repositories', exact: true })).toBeVisible();
  await expectNoErrorBoundary(page);
}

async function createRepository(page: Page, name: string) {
  await page.getByRole('button', { name: 'New repository' }).click();
  await page.getByLabel('Name').fill(name);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page.getByRole('link', { name: new RegExp(name) })).toBeVisible();
}

test.describe('Repositories', () => {
  test('a project links through to its repositories, which start empty', async ({ page }) => {
    await openRepositories(page);

    await expect(page.getByText('No repositories yet')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('a new repository is created and reported as empty until it has a commit', async ({
    page,
  }) => {
    await openRepositories(page);
    await createRepository(page, 'payments-api');

    // "Empty" is a real state, not an error: a repository exists before its first commit.
    await expect(page.getByText('Empty')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('opening an empty repository says so rather than failing', async ({ page }) => {
    await openRepositories(page);
    await createRepository(page, 'fresh-repo');

    await page.getByRole('link', { name: /fresh-repo/ }).click();

    await expect(page.getByRole('heading', { name: 'fresh-repo', exact: true })).toBeVisible();
    await expect(page.getByText('This repository is empty')).toBeVisible();
    // Nothing to browse yet, so there must be no tabs promising otherwise.
    await expect(page.getByRole('tab', { name: 'Files' })).toHaveCount(0);
    await expectNoErrorBoundary(page);
  });

  test('the server rejects a name that is unsafe as a directory, and the UI says why', async ({
    page,
  }) => {
    await openRepositories(page);

    await page.getByRole('button', { name: 'New repository' }).click();
    await page.getByLabel('Name').fill('../escape');
    await page.getByRole('button', { name: 'Create', exact: true }).click();

    // The rule lives on the server, because the name becomes a path component there. What matters
    // here is that the refusal reaches the user instead of failing silently.
    await expect(page.getByRole('alert')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('a duplicate name within the project is refused', async ({ page }) => {
    await openRepositories(page);
    await createRepository(page, 'duplicate-me');

    await page.getByRole('button', { name: 'New repository' }).click();
    await page.getByLabel('Name').fill('duplicate-me');
    await page.getByRole('button', { name: 'Create', exact: true }).click();

    await expect(page.getByRole('alert')).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('a repository browser deep link survives a reload', async ({ page }) => {
    await openRepositories(page);
    await createRepository(page, 'reload-me');
    await page.getByRole('link', { name: /reload-me/ }).click();
    await expect(page.getByRole('heading', { name: 'reload-me', exact: true })).toBeVisible();

    const url = page.url();
    await page.reload();

    // State lives in the URL, so a reload — or someone else opening the link — lands in the same
    // place rather than back at the root.
    expect(page.url()).toBe(url);
    await expect(page.getByRole('heading', { name: 'reload-me', exact: true })).toBeVisible();
    await expectNoErrorBoundary(page);
  });
});
