import { expect, test as base } from '@playwright/test';

/**
 * Every spec's `test`, failing the test if the page reported a Content-Security-Policy violation.
 *
 * A CSP that blocks something rarely throws: the browser drops the script, style or request and
 * logs it, and the page carries on half-working. Without this check a too-strict policy passes the
 * suite as long as no assertion happens to depend on what it blocked.
 */
export const test = base.extend<{ cspViolations: string[] }>({
  cspViolations: [
    async ({ page }, use) => {
      const violations: string[] = [];
      page.on('console', (message) => {
        if (message.text().includes('Content Security Policy')) violations.push(message.text());
      });
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (event) => {
          console.error(
            `Content Security Policy violation: ${event.violatedDirective} blocked ${event.blockedURI}`,
          );
        });
      });
      await use(violations);
      expect(violations, 'CSP violations').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
