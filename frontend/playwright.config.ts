import { defineConfig, devices } from '@playwright/test';

/**
 * Browser-level tests against a running stack.
 *
 * These exist because component tests could not catch the class of bug that
 * broke the app after sign-in: they rendered App inside a provider tree the
 * real entry point did not have, so the suite stayed green while every
 * data-driven screen threw on mount. Only something that actually loads the
 * page in a browser exercises the real composition.
 *
 * The stack must already be running (openapp.bat). These tests deliberately do
 * not start it: the services take a while to boot, and a test run that silently
 * launches background processes is hard to reason about when it fails.
 */
export default defineConfig({
  testDir: './e2e',
  // Generous, because the first request after a cold JVM start can be slow.
  timeout: 45_000,
  expect: { timeout: 10_000 },

  // Serial locally: the tests create accounts and organizations against one
  // shared database, so parallel workers would interfere with each other.
  fullyParallel: false,
  workers: 1,

  // A test that only passes on a retry is flaky, and in CI that must be visible
  // rather than smoothed over.
  retries: process.env.CI ? 1 : 0,
  forbidOnly: !!process.env.CI,

  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
