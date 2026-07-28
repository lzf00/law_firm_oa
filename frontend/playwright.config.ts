import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 120_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [['line'], ['html', { outputFolder: 'playwright-report', open: 'never' }]]
    : 'line',
  use: {
    baseURL: process.env.OA_WEB_URL ?? 'http://localhost:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop-zh',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'desktop-en',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'mobile-zh',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 390, height: 844 },
      },
    },
  ],
})
