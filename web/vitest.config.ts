import { defineConfig } from 'vitest/config'

// Unit tests run in a plain Node environment (the helpers under test are pure).
// Playwright owns the `e2e/` directory, so it is excluded here.
export default defineConfig({
  test: {
    environment: 'node',
    // pin TZ=UTC for every run (incl. bare `npx vitest`), so the date/clock helpers
    // are deterministic regardless of the machine's local zone — see issue #148.
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.ts'],
    exclude: ['node_modules', 'e2e'],
  },
})
