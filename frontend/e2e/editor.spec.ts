import type { Page } from '@playwright/test';

import { expect, test } from './fixtures';
import { navTo, signIn, signUp, uniqueAccount } from './support';

/** Creates an organization, project and repository; returns the repository page URL. */
async function openRepository(page: Page): Promise<string> {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `Editor Org ${Date.now()}`;
  await navTo(page, 'Organizations');
  await page.getByRole('button', { name: 'Create organization' }).click();
  await page.getByLabel('Name').fill(orgName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: new RegExp(orgName, 'i') })
    .click();
  await page.getByRole('button', { name: 'Create project' }).click();
  await page.getByLabel('Name').fill('Edited Project');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: /Edited Project/i })
    .click();
  await page.getByRole('link', { name: 'Repositories' }).click();
  await page.getByRole('button', { name: 'New repository' }).click();
  await page.getByLabel('Name').fill('edited-repo');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.getByRole('link', { name: /edited-repo/ }).click();
  await expect(page.getByRole('heading', { name: 'edited-repo', exact: true })).toBeVisible();
  return page.url();
}

async function addFile(page: Page, path: string, content: string) {
  await page.getByRole('button', { name: /add a file/i }).click();
  await page.getByLabel('Path').fill(path);
  await page.getByLabel('Content').fill(content);
  await page.getByLabel('Commit message').fill(`Add ${path}`);
  await page.getByRole('button', { name: 'Commit' }).click();
  await expect(page.getByRole('button', { name: /add a file/i })).toBeVisible();
}

/**
 * Opens a file by clicking through the tree, as a user does. A page.goto would reload the app and
 * throw away the working set, which lives in memory until committed.
 */
async function openFile(page: Page, path: string) {
  await page.getByRole('tab', { name: 'Files' }).click();
  const root = page
    .getByRole('navigation', { name: 'Breadcrumb' })
    .getByRole('button', { name: 'edited-repo', exact: true });
  if (await root.count()) await root.click();
  for (const segment of path.split('/')) {
    const escaped = segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    await page.getByRole('button', { name: new RegExp(`^${escaped} (file|directory)`) }).click();
  }
}

/** Replaces the editor's whole content, the way a user would: select all, then type. */
async function editTo(page: Page, path: string, text: string) {
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const editor = page.getByRole('textbox', { name: `Editing ${path}` });
  await expect(editor).toBeVisible();
  await editor.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.insertText(text);
  await page.getByRole('button', { name: 'Done editing' }).click();
}

test.describe('Editor', () => {
  test('edits and a deletion across files land as one commit', async ({ page }) => {
    const repo = await openRepository(page);
    await addFile(page, 'README.md', '# Old');
    await addFile(page, 'src/app.ts', 'export const a = 1;');
    await addFile(page, 'obsolete.txt', 'remove me');

    await openFile(page, 'README.md');
    await editTo(page, 'README.md', '# New title');
    await openFile(page, 'src/app.ts');
    await editTo(page, 'src/app.ts', 'export const a = 2;');
    await openFile(page, 'obsolete.txt');
    await page.getByRole('button', { name: 'Delete', exact: true }).click();

    const panel = page.getByRole('form', { name: 'Uncommitted changes' });
    await expect(panel.getByRole('listitem')).toHaveCount(3);
    await expect(panel.getByText('Deleted')).toBeVisible();
    await panel.getByLabel('Commit message').fill('Edit two files, remove one');
    await panel.getByRole('button', { name: 'Commit 3 changes' }).click();
    await expect(panel).toBeHidden();

    // One commit for all three, on top of the three single-file commits.
    await page.goto(repo);
    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page.getByText('Edit two files, remove one')).toBeVisible();

    await openFile(page, 'README.md');
    await expect(page.getByText('# New title')).toBeVisible();
    await openFile(page, 'src/app.ts');
    await expect(page.getByText('export const a = 2;')).toBeVisible();
    await page
      .getByRole('navigation', { name: 'Breadcrumb' })
      .getByRole('button', { name: 'edited-repo', exact: true })
      .click();
    await expect(page.getByRole('button', { name: /^README\.md file/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^obsolete\.txt file/ })).toHaveCount(0);
  });

  test('a commit made by someone else meanwhile is not overwritten', async ({ page }) => {
    const repo = await openRepository(page);
    await addFile(page, 'notes.txt', 'v1');

    await openFile(page, 'notes.txt');
    await editTo(page, 'notes.txt', 'my edit');

    // Another writer commits to the branch after the editor pinned its base. Same session, so
    // the request carries the session cookie and the app's header, like a second tab would.
    const [, , organizationId, , projectId, , repositoryId] = new URL(repo).pathname.split('/');
    const response = await page.request.post(
      `/api/v1/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/files`,
      {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
        data: { path: 'other.txt', content: 'theirs', message: 'Their commit' },
      },
    );
    expect(response.status()).toBe(201);

    const panel = page.getByRole('form', { name: 'Uncommitted changes' });
    await panel.getByLabel('Commit message').fill('Mine');
    await panel.getByRole('button', { name: 'Commit 1 change' }).click();

    await expect(panel.getByRole('alert')).toContainText(/new commits since you started editing/i);
    await panel.getByRole('button', { name: 'Discard all and reload' }).click();
    await expect(panel).toBeHidden();

    // Their commit stands, and the file still has the content it had.
    await openFile(page, 'notes.txt');
    await expect(page.getByText('v1', { exact: true })).toBeVisible();

    // No model API key in CI: the AI button is there, disabled, and says why - no stand-in answer.
    await expect(page.getByRole('button', { name: 'Explain with AI' })).toBeDisabled();
    await expect(
      page.getByText('AI assistance is not configured on this deployment.'),
    ).toBeVisible();
  });
});
