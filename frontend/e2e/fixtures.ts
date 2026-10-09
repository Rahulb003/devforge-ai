import { expect, test as base } from '@playwright/test';

/**
 * Every spec's `test`, failing the test if the page reported a Content-Security-Policy violation.
 *
 * A CSP that blocks something rarely throws: the browser drops the script, style or request and
 * logs it, and the page carries on half-working. Without this check a too-strict policy passes the
 * suite as long as no assertion happens to depend on what it blocked.
 *
 * Violations are taken from the securitypolicyviolation event rather than the console, because the
 * event names the element involved, which the one exemption below needs.
 */
export const test = base.extend<{ cspViolations: string[] }>({
  cspViolations: [
    async ({ page }, use) => {
      const violations: string[] = [];
      page.on('console', (message) => {
        if (message.text().startsWith('CSP-VIOLATION ')) violations.push(message.text());
      });
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (event) => {
          const target = event.target instanceof Element ? event.target : null;
          // The one exemption: when typed or pasted text replaces a selection in the code editor,
          // Chrome's own editing engine tries to put a style attribute on the new content. The
          // policy blocks it, CodeMirror redraws the line from its own state, and nothing is lost.
          // It is the browser's internals, not the app's, and only inside the editable code area.
          if (event.violatedDirective === 'style-src-attr' && target?.closest('.cm-content')) {
            return;
          }
          console.error(
            `CSP-VIOLATION ${event.violatedDirective} blocked ${event.blockedURI} on ${
              target?.outerHTML.slice(0, 120) ?? 'document'
            }`,
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
