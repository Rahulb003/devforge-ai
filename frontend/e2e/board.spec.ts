import type { Page } from '@playwright/test';

import { expect, test } from './fixtures';
import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

/** Creates an organization and a project, then opens that project's board. */
async function openBoard(page: Page) {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `Board Org ${Date.now()}`;
  await navTo(page, 'Organizations');
  await page.getByRole('button', { name: 'Create organization' }).click();
  await page.getByLabel('Name').fill(orgName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.getByRole('main').getByRole('link', { name: new RegExp(orgName, 'i') }).click();

  await page.getByRole('button', { name: 'Create project' }).click();
  await page.getByLabel('Name').fill('Board Project');
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  // Project cards link through to the board.
  await page.getByRole('main').getByRole('link', { name: /Board Project/i }).click();
  await expect(page.getByRole('heading', { name: 'Board Project' })).toBeVisible();
}

async function createTask(page: Page, title: string) {
  await page.getByRole('button', { name: 'New task' }).click();
  await page.getByLabel('Title').fill(title);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  // The title also appears in the move select's sr-only label, so match the
  // card button specifically rather than any text node.
  await expect(page.getByRole('button', { name: new RegExp(title) })).toBeVisible();
}

test.describe('Kanban board', () => {
  test('a project card opens its board with every column', async ({ page }) => {
    await openBoard(page);

    // Every status is rendered, including empty ones: a board that hides empty
    // columns has nowhere to drop the first card.
    for (const column of ['Backlog', 'To do', 'In progress', 'In review', 'Blocked', 'Done']) {
      await expect(page.getByRole('region', { name: column })).toBeVisible();
    }
    await expectNoErrorBoundary(page);
  });

  test('a new task appears in the backlog', async ({ page }) => {
    await openBoard(page);
    await createTask(page, 'Write the docs');

    const backlog = page.getByRole('region', { name: 'Backlog' });
    await expect(backlog.getByRole('button', { name: /Write the docs/ })).toBeVisible();
    await expectNoErrorBoundary(page);
  });

  test('a task can be moved between columns from the keyboard', async ({ page }) => {
    await openBoard(page);
    await createTask(page, 'Movable task');

    // The select is the accessible equivalent of dragging, and is what a
    // keyboard or screen-reader user relies on. Dragging is not testable here
    // and is not the only path on purpose.
    await page.getByLabel('Move Movable task to another column').selectOption('IN_PROGRESS');

    const inProgress = page.getByRole('region', { name: 'In progress' });
    await expect(inProgress.getByRole('button', { name: /Movable task/ })).toBeVisible();

    const backlog = page.getByRole('region', { name: 'Backlog' });
    await expect(backlog.getByRole('button', { name: /Movable task/ })).toHaveCount(0);
  });

  test('the task drawer opens with details and accepts a comment', async ({ page }) => {
    await openBoard(page);
    await createTask(page, 'Discussed task');

    await page.getByRole('button', { name: /Discussed task/ }).click();

    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('heading', { name: 'Discussed task' })).toBeVisible();

    await drawer.getByLabel('Add a comment').fill('This needs review.');
    await drawer.getByRole('button', { name: 'Post' }).click();
    await expect(drawer.getByText('This needs review.')).toBeVisible();

    await expectNoErrorBoundary(page);
  });

  test('a label can be added and removed from a task', async ({ page }) => {
    await openBoard(page);
    await createTask(page, 'Labelled task');

    await page.getByRole('button', { name: /Labelled task/ }).click();
    const drawer = page.getByRole('dialog');

    await drawer.getByLabel('Add a label').fill('backend');
    await drawer.getByRole('button', { name: 'Add' }).click();
    await expect(drawer.getByText('backend', { exact: true })).toBeVisible();

    await drawer.getByRole('button', { name: 'Remove label backend' }).click();
    await expect(drawer.getByText('No labels.')).toBeVisible();
  });

  test('the drawer closes with Escape', async ({ page }) => {
    await openBoard(page);
    await createTask(page, 'Closable task');

    await page.getByRole('button', { name: /Closable task/ }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // A modal that traps the user is worse than no modal.
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('a task can be deleted from the drawer', async ({ page }) => {
    await openBoard(page);
    await createTask(page, 'Doomed task');

    await page.getByRole('button', { name: /Doomed task/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete task' }).click();

    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Doomed task/ })).toHaveCount(0);
  });

  test('sprints can be created and started, and a second cannot start', async ({ page }) => {
    await openBoard(page);

    const sprints = page.getByRole('region', { name: 'Sprints' });
    await page.getByRole('button', { name: 'New sprint' }).click();
    await page.getByLabel('Sprint name').fill('Sprint 1');
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await expect(sprints.getByText('Sprint 1')).toBeVisible();
    await expect(sprints.getByText('PLANNED').first()).toBeVisible();

    await page.getByRole('button', { name: 'New sprint' }).click();
    await page.getByLabel('Sprint name').fill('Sprint 2');
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await expect(sprints.getByText('Sprint 2')).toBeVisible();

    // Addressed by name rather than by position. `.first()` depended on the list's order, which
    // was not stable when two sprints were created in the same millisecond — so this intermittently
    // started the same sprint twice, which the server allows, and then found no error to assert.
    const sprintRow = (name: string) =>
      sprints.locator('li').filter({ hasText: name });

    await sprintRow('Sprint 1').getByRole('button', { name: 'Start' }).click();
    await expect(sprintRow('Sprint 1').getByText('ACTIVE')).toBeVisible();

    // Two concurrent sprints make velocity meaningless, so the server refuses
    // and the UI must show why rather than appearing to do nothing.
    await sprintRow('Sprint 2').getByRole('button', { name: 'Start' }).click();
    await expect(sprints.getByRole('alert')).toContainText(/already active/i);
  });
});
