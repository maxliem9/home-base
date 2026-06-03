# Web E2E Tests (Playwright)

End-to-end tests for the HomeBase web app. The backend (`/api/v1/**`) and the
WebSocket are mocked in-browser via `page.route` / a fake `WebSocket`, so the
suite runs **without** a live backend or database.

## Run

```bash
cd web
npm run test:e2e        # headless
npm run test:e2e:ui     # interactive UI mode
```

The Playwright config starts the Vite dev server (`npm run dev`, port 5173)
automatically and reuses an already-running one locally.

First-time setup downloads the browser:

```bash
npx playwright install chromium
```

## Layout

- `helpers/mockApi.ts` — in-memory backend stub (`MockApi`) + `todo()` factory.
- `auth.spec.ts` — login form, validation, error handling, logout.
- `todos.spec.ts` — inbox rendering, add/plan/complete/delete flows, tab nav.
