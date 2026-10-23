import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end walkthrough on its own stack, never the demo database:
 * an in-memory Postgres (`postgres-e2e`, port 5433, recreated each run), a backend on :8081 seeded with the
 * demo profile, and Vite on :5174 proxying to it. Playwright starts and stops all of it.
 */
const BACKEND = 'http://localhost:8081'
const FRONTEND = 'http://localhost:5174'

export default defineConfig({
  testDir: './e2e',
  globalTeardown: './e2e/teardown.mjs',
  timeout: 240_000,
  expect: { timeout: 10_000 },
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: FRONTEND,
    ...devices['Pixel 7'],
    trace: 'retain-on-failure',
    actionTimeout: 15_000,
  },
  webServer: [
    {
      command: 'node e2e/start-backend.mjs',
      url: `${BACKEND}/api/turfs`,
      timeout: 300_000,
      reuseExistingServer: false,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npx vite --port 5174 --strictPort',
      url: FRONTEND,
      timeout: 60_000,
      reuseExistingServer: false,
      env: { TURFUP_API: BACKEND },
    },
  ],
})
