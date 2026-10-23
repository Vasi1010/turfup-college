import { defineConfig } from 'vitest/config'

// Unit tests only; the Playwright walkthrough in e2e/ runs with `npx playwright test`.
export default defineConfig({
  test: { include: ['src/**/*.test.ts'] },
})
