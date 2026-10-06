import { expect, test, type Page } from '@playwright/test';

import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

/** Creates an organization, project and repository, then opens that repository. */
async function openRepository(page: Page, repoName: string) {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `Docs Org ${Date.now()}`;
  await navTo(page, 'Organizations');
  await page.getByRole('button', { name: 'Create organization' }).click();
  await page.getByLabel('Name').fill(orgName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: new RegExp(orgName, 'i') })
    .click();

  await page.getByRole('button', { name: 'Create project' }).click();
  await page.getByLabel('Name').fill('Documented Project');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: /Documented Project/i })
    .click();

  await page.getByRole('link', { name: 'Repositories' }).click();
  await page.getByRole('button', { name: 'New repository' }).click();
  await page.getByLabel('Name').fill(repoName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.getByRole('link', { name: new RegExp(repoName) }).click();
  await expect(page.getByRole('heading', { name: repoName })).toBeVisible();
}

async function addFile(page: Page, path: string, content: string, message: string) {
  await page.getByRole('button', { name: /add a file/i }).click();
  await page.getByLabel('Path').fill(path);
  await page.getByLabel('Content').fill(content);
  await page.getByLabel('Commit message').fill(message);
  await page.getByRole('button', { name: 'Commit' }).click();
  await expect(page.getByRole('button', { name: /add a file/i })).toBeVisible();
}

test.describe('Documentation', () => {
  test('generates documents describing what is actually in the repository', async ({ page }) => {
    await openRepository(page, 'documented-repo');
    await addFile(
      page,
      'src/UserController.java',
      '@RestController\n'
        + '@RequestMapping("/api/v1/users")\n'
        + 'public class UserController {\n'
        + '  /** Lists users. */\n'
        + '  @GetMapping\n'
        + '  public List<User> list() { return List.of(); }\n'
        + '}\n',
      'Add UserController',
    );

    await page.getByRole('link', { name: 'Docs' }).click();
    await expect(page.getByRole('heading', { name: 'Documentation' })).toBeVisible();
    await expect(page.getByText('No documentation yet')).toBeVisible();

    await page.getByRole('button', { name: 'Generate' }).click();

    await expect(page.getByRole('tab', { name: 'Overview' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'API surface' })).toBeVisible();

    await page.getByRole('tab', { name: 'API surface' }).click();
    // The endpoint declared above, with the class-level prefix applied.
    await expect(page.getByText('/api/v1/users')).toBeVisible();
    // And the document's own statement of what it cannot see.
    await expect(page.getByText(/Limits of this scan/)).toBeVisible();

    await page.getByRole('tab', { name: 'Overview' }).click();
    await expect(page.getByText(/Repository overview/)).toBeVisible();

    await expectNoErrorBoundary(page);
  });

  test('an empty repository offers no docs link, since there is nothing to document', async ({
    page,
  }) => {
    await openRepository(page, 'undocumented-repo');

    await expect(page.getByText('This repository is empty')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Docs' })).toHaveCount(0);
    await expectNoErrorBoundary(page);
  });
});
