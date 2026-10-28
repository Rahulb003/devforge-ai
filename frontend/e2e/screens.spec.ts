import { expect, test } from './fixtures';
import { expectNoErrorBoundary, signIn, signUp, uniqueAccount } from './support';

/**
 * Every screen, with real data behind it, on a desktop and at phone width: no page error, no
 * server error, no error boundary, and no page wider than the screen.
 *
 * Grew out of a crawl that found the project board and the repository page scrolling sideways on
 * a phone - toolbars that never wrapped, and a clone URL wider than the screen - which hid the
 * "Pull requests" button off the right edge. No other test looked at a screen that narrow.
 */
test.describe('Every screen', () => {
  test('renders cleanly with real data, on a desktop and on a phone', async ({ page, baseURL }) => {
    test.setTimeout(180_000);
    const account = uniqueAccount();
    await signUp(page, account);
    await signIn(page, account.email, account.password);

    // Data through the API, as this user: the screens, not the forms, are under test here.
    const api = `${baseURL}/api/v1`;
    const post = async (path: string, body: unknown, token?: string) => {
      const res = await fetch(api + path, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(body),
      });
      return (await res.json()).data;
    };
    const token = (
      await post('/auth/login', { usernameOrEmail: account.email, password: account.password })
    ).accessToken;
    const org = (await post('/organizations', { name: 'Screens', slug: account.username }, token))
      .id;
    const project = (
      await post(`/organizations/${org}/projects`, { name: 'Calc', projectKey: 'CALC' }, token)
    ).id;
    const P = `/organizations/${org}/projects/${project}`;
    const repo = (await post(`${P}/repositories`, { name: 'calc' }, token)).id;
    const R = `${P}/repositories/${repo}`;
    await post(
      `${R}/files`,
      {
        path: 'calc.py',
        content: 'def add(a, b):\n    return a + b\n',
        message: 'add',
        branch: 'main',
      },
      token,
    );
    await post(`${P}/tasks`, { title: 'Write tests' }, token);
    await post(`${P}/chat/messages`, { body: 'hello' }, token);

    const screens = [
      '/',
      '/organizations',
      `/organizations/${org}`,
      P,
      `${P}/repositories`,
      `${P}/chat`,
      `${P}/run`,
      `${P}/members`,
      `${P}/analytics`,
      R,
      `${R}/review`,
      `${R}/docs`,
      `${R}/pipelines`,
      `${R}/pull-requests`,
      '/notifications',
      '/settings',
    ];

    const problems: string[] = [];
    let where = '';
    page.on('pageerror', (e) => problems.push(`${where}: page error ${e.message.slice(0, 120)}`));
    page.on('response', (r) => {
      if (r.url().includes('/api/') && r.status() >= 500 && r.status() !== 503) {
        problems.push(`${where}: ${r.status()} from ${new URL(r.url()).pathname}`);
      }
    });

    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      for (const screen of screens) {
        where = `${screen.replace(org, '{org}').replace(project, '{p}').replace(repo, '{r}')} at ${width}px`;
        await page.goto(screen);
        // Not 'networkidle': chat holds a live stream open and run pages poll, so it never comes.
        await page.waitForLoadState('load');
        await page.waitForTimeout(600);
        await expectNoErrorBoundary(page);
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth - window.innerWidth,
        );
        if (overflow > 4) problems.push(`${where}: scrolls sideways by ${overflow}px`);
      }
    }
    expect(problems).toEqual([]);
  });
});
