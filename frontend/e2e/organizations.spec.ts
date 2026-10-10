import { expect, test } from './fixtures';
import { expectNoErrorBoundary, navTo, signIn, signUp, uniqueAccount } from './support';

test.describe('Organization membership', () => {
  test('an invited person joins, and the owner sees them as a member', async ({
    page,
    browser,
  }) => {
    const owner = uniqueAccount();
    await signUp(page, owner);
    await signIn(page, owner.email, owner.password);

    const orgName = `Team Org ${Date.now()}`;
    await navTo(page, 'Organizations');
    await page.getByRole('button', { name: 'Create organization' }).click();
    await page.getByLabel('Name').fill(orgName);
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await page
      .getByRole('main')
      .getByRole('link', { name: new RegExp(orgName, 'i') })
      .click();

    // The owner invites an address before its owner has even signed up.
    const invitee = uniqueAccount();
    const invite = page.getByRole('form', { name: 'Invite someone' });
    await invite.getByLabel('Invite by email').fill(invitee.email);
    await invite.getByRole('button', { name: 'Send invitation' }).click();
    await expect(page.getByRole('status')).toContainText(`Invitation sent to ${invitee.email}`);
    await expect(page.getByRole('list', { name: 'Pending invitations' })).toContainText(
      invitee.email,
    );

    // The invitee, in a separate browser, signs up with that address and finds the invitation.
    // A context made here does not inherit the configured baseURL, so it is passed on.
    const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
    const theirs = await other.newPage();
    await signUp(theirs, invitee);
    await signIn(theirs, invitee.email, invitee.password);
    await navTo(theirs, 'Organizations');
    const inbox = theirs.getByRole('list', { name: 'Your invitations' });
    await expect(inbox).toContainText(orgName);
    await inbox.getByRole('button', { name: `Join ${orgName}` }).click();
    await expect(
      theirs.getByRole('main').getByRole('link', { name: new RegExp(orgName, 'i') }),
    ).toBeVisible();
    await expectNoErrorBoundary(theirs);
    await other.close();

    // Back with the owner: a member now, and the invitation is no longer pending.
    await page.reload();
    await expect(page.getByRole('list', { name: 'Members' })).toContainText(invitee.username);
    await expect(page.getByRole('list', { name: 'Pending invitations' })).toHaveCount(0);
  });
});
