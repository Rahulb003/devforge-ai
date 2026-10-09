import type { Page } from '@playwright/test';

import { expect, test } from './fixtures';
import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

async function openBoard(page: Page) {
  const account = uniqueAccount();
  await signUp(page, account);
  await signIn(page, account.email, account.password);

  const orgName = `Chat Org ${Date.now()}`;
  await navTo(page, 'Organizations');
  await page.getByRole('button', { name: 'Create organization' }).click();
  await page.getByLabel('Name').fill(orgName);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: new RegExp(orgName, 'i') })
    .click();

  await page.getByRole('button', { name: 'Create project' }).click();
  await page.getByLabel('Name').fill('Chat Project');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page
    .getByRole('main')
    .getByRole('link', { name: /Chat Project/i })
    .click();
  await expect(page.getByRole('heading', { name: 'Chat Project' })).toBeVisible();
}

test.describe('Chat and activity', () => {
  test('a message posted in project chat appears and can be deleted', async ({ page }) => {
    await openBoard(page);
    await page.getByRole('link', { name: 'Chat' }).click();
    await expect(page.getByText('No messages yet')).toBeVisible();

    await page.getByLabel('Message').fill('Hello from the e2e suite');
    await page.getByRole('button', { name: 'Send' }).click();
    await expect(page.getByText('Hello from the e2e suite')).toBeVisible();

    await page.getByRole('button', { name: /delete your message/i }).click();
    await expect(page.getByText('Message deleted')).toBeVisible();
    await expect(page.getByText('Hello from the e2e suite')).toHaveCount(0);
    await expectNoErrorBoundary(page);
  });

  test('the activity page loads and states where its numbers come from', async ({ page }) => {
    await openBoard(page);
    await page.getByRole('link', { name: 'Activity' }).click();

    await expect(page.getByRole('heading', { name: 'Project activity' })).toBeVisible();
    // The standalone stack has no broker, so these are zero — and the page must say why.
    await expect(page.getByText(/Counted from domain events/)).toBeVisible();
    await expectNoErrorBoundary(page);

    // The project's creator is its admin, so sees the audit log rather than the refusal.
    await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible();
    await expect(page.getByText(/Only project admins/)).toHaveCount(0);
    if (process.env.E2E_FULL_STACK) {
      // With a real broker the whole path runs: project-service's outbox, Kafka, analytics-service
      // writing the log. The creation of this very project must arrive in it.
      const log = page.getByRole('table', { name: 'Audit log' });
      await expect(async () => {
        await page.reload();
        await expect(log.getByText('Project created')).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 30_000 });
    } else {
      // Standalone runs without a broker, so nothing reaches the log - and it says so.
      await expect(page.getByText('Nothing recorded yet.')).toBeVisible();
    }
  });
  test('a message appears for another viewer live, without reloading', async ({
    page,
    browser,
  }) => {
    await openBoard(page);
    await page.getByRole('link', { name: 'Chat' }).click();
    await expect(page.getByText(/new messages appear as they are sent/i)).toBeVisible();
    const chatUrl = page.url();

    // A second, independent session posting into the same channel. Same account, so no invite flow
    // is needed - what matters is that the first window is told without reloading.
    const storage = await page.context().storageState();
    const other = await browser.newContext({ storageState: storage });
    const otherPage = await other.newPage();
    await otherPage.goto(chatUrl);
    await otherPage.getByLabel('Message').fill('pushed to the other window');
    await otherPage.getByRole('button', { name: 'Send' }).click();

    // 10s, well under the 30s fallback poll: arriving within it proves the stream delivered it,
    // through both the dev proxy and the gateway, rather than the poll catching up.
    await expect(page.getByText('pushed to the other window')).toBeVisible({ timeout: 10_000 });
    await other.close();
  });
});
