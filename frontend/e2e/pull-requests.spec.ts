import type { Page } from '@playwright/test';

import { expect, test } from './fixtures';
import { navTo, signIn, signUp, uniqueAccount } from './support';

async function openRepository(page: Page) {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `PR Org ${Date.now()}`;
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
  await page.getByLabel('Name').fill('pr-repo');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.getByRole('link', { name: /pr-repo/ }).click();
  await expect(page.getByRole('heading', { name: 'pr-repo' })).toBeVisible();
}

/** Commits a file to whichever branch the page is showing. */
async function addFile(page: Page, path: string, content: string) {
  await page.getByRole('button', { name: /add a file/i }).click();
  await page.getByLabel('Path').fill(path);
  await page.getByLabel('Content').fill(content);
  await page.getByLabel('Commit message').fill(`Change ${path}`);
  await page.getByRole('button', { name: 'Commit' }).click();
  await expect(page.getByRole('button', { name: /add a file/i })).toBeVisible();
}

async function newBranch(page: Page, name: string) {
  await page.getByRole('button', { name: 'New branch' }).click();
  await page.getByLabel('Branch name').fill(name);
  await page.getByRole('button', { name: 'Create branch' }).click();
  await expect(page.getByRole('combobox', { name: 'Branch' })).toHaveValue(name);
}

async function switchBranch(page: Page, name: string) {
  await page.getByRole('combobox', { name: 'Branch' }).selectOption(name);
}

async function openPullRequest(page: Page, source: string, title: string) {
  await page.getByRole('link', { name: 'Pull requests' }).click();
  await page.getByRole('button', { name: 'New pull request' }).click();
  const form = page.getByRole('form', { name: 'New pull request' });
  await form.getByLabel('From branch').selectOption(source);
  await form.getByLabel('Title').fill(title);
  await form.getByRole('button', { name: 'Open pull request' }).click();
  await expect(page.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
}

test.describe('Pull requests', () => {
  test('a branch is proposed, reviewed and merged into main', async ({ page }) => {
    await openRepository(page);
    await addFile(page, 'README.md', '# readme');
    await newBranch(page, 'feature/login');
    await addFile(page, 'login.ts', 'export const login = true;');

    await openPullRequest(page, 'feature/login', 'Add login');
    await expect(page.getByText('No conflicts with main.')).toBeVisible();
    // Only what the branch adds.
    const changed = page.getByRole('region', { name: 'Changed files' });
    await expect(changed.getByRole('listitem')).toHaveCount(1);
    await expect(changed.getByText('login.ts')).toBeVisible();

    await page.getByRole('button', { name: 'Merge', exact: true }).click();
    await expect(page.getByText('Merged', { exact: true })).toBeVisible();
    await expect(page.getByText(/Merged as [0-9a-f]{7}/)).toBeVisible();

    // main now has the file.
    await page.getByRole('link', { name: 'All pull requests' }).click();
    await page.getByRole('link', { name: /Back to pr-repo/ }).click();
    await switchBranch(page, 'main');
    await expect(page.getByRole('button', { name: /^login\.ts file/ })).toBeVisible();
  });

  test('conflicting changes block the merge, and the pull request can be closed', async ({
    page,
  }) => {
    await openRepository(page);
    await addFile(page, 'config.txt', 'original');
    await newBranch(page, 'feature/config');
    await page.getByRole('button', { name: /^config\.txt file/ }).click();
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
    const editor = page.getByRole('textbox', { name: 'Editing config.txt' });
    await editor.click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('feature value');
    await page.getByRole('button', { name: 'Done editing' }).click();
    const panel = page.getByRole('form', { name: 'Uncommitted changes' });
    await panel.getByLabel('Commit message').fill('Feature config');
    await panel.getByRole('button', { name: 'Commit 1 change' }).click();
    await expect(panel).toBeHidden();

    // main changes the same line differently.
    await switchBranch(page, 'main');
    await page.getByRole('button', { name: /^config\.txt file/ }).click();
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
    await page.getByRole('textbox', { name: 'Editing config.txt' }).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('main value');
    await page.getByRole('button', { name: 'Done editing' }).click();
    await panel.getByLabel('Commit message').fill('Main config');
    await panel.getByRole('button', { name: 'Commit 1 change' }).click();
    await expect(panel).toBeHidden();

    await openPullRequest(page, 'feature/config', 'Change config');
    await expect(page.getByRole('list', { name: 'Conflicting files' })).toContainText('config.txt');
    await expect(page.getByRole('button', { name: 'Merge', exact: true })).toBeDisabled();

    await page.getByRole('button', { name: 'Close without merging' }).click();
    await expect(page.getByText('Closed', { exact: true })).toBeVisible();
  });

  test('a merge rule holds the merge until it is met, and the discussion is kept', async ({
    page,
  }) => {
    await openRepository(page);
    await addFile(page, 'README.md', '# readme');
    await newBranch(page, 'feature/docs');
    await addFile(page, 'docs.md', 'how it works');

    // The repository's creator is a project admin, so may set the rule.
    await page.getByRole('link', { name: 'Pull requests' }).click();
    const rules = page.getByRole('form', { name: 'Merge rules' });
    await rules.getByLabel('Approvals required to merge').fill('1');
    await rules.getByRole('button', { name: 'Save' }).click();
    await expect(rules.getByRole('button', { name: 'Save' })).toBeHidden();

    await page.getByRole('button', { name: 'New pull request' }).click();
    const form = page.getByRole('form', { name: 'New pull request' });
    await form.getByLabel('From branch').selectOption('feature/docs');
    await form.getByLabel('Title').fill('Document it');
    await form.getByRole('button', { name: 'Open pull request' }).click();

    // The author cannot approve their own change, so with one approval required it cannot merge.
    await expect(page.getByText('0 of 1 required approval of the current changes')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0);
    await expect(page.getByText('Needs 1 more approval before it can merge.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Merge', exact: true })).toBeDisabled();

    const discussion = page.getByRole('form', { name: 'Add a comment' });
    await discussion.getByLabel('Comment').fill('Waiting on a reviewer.');
    await discussion.getByRole('button', { name: 'Comment' }).click();
    const comments = page.getByRole('list', { name: 'Comments' });
    await expect(comments.getByText('Waiting on a reviewer.')).toBeVisible();
    await comments.getByRole('button', { name: 'Delete comment' }).click();
    await expect(page.getByText('No comments yet.')).toBeVisible();

    // Relaxing the rule lets it merge.
    await page.getByRole('link', { name: 'All pull requests' }).click();
    await rules.getByLabel('Approvals required to merge').fill('0');
    await rules.getByRole('button', { name: 'Save' }).click();
    await expect(rules.getByRole('button', { name: 'Save' })).toBeHidden();
    await page.getByRole('link', { name: /Document it/ }).click();
    await page.getByRole('button', { name: 'Merge', exact: true }).click();
    await expect(page.getByText('Merged', { exact: true })).toBeVisible();
  });
});
