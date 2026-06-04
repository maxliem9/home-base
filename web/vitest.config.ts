import { defineConfig } from 'vitest/config'

// Unit tests run in a plain Node environment (the helpers under test are pure).
// Playwright owns the `e2e/` directory, so it is excluded here.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    exclude: ['node_modules', 'e2e'],
  },
})
