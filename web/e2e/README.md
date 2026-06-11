# Web E2E Tests (Playwright)

End-to-end tests for the HomeBase web app. The backend (`/api/v1/**`) and the
WebSocket are mocked in-browser via `page.route` / a fake `WebSocket`, so the
suite runs **without** a live backend or database.

Most views reflect their mutations from the REST response, so the fake socket
can stay silent. Where the realtime echo matters, the mock tags responses with
an `x-ws-frames` header (time + todo-list mutations, replayed after the fetch
resolves) or `x-ws-frames-pre` (todo creation, delivered synchronously before
the fetch resolves to pin the echo-beats-REST ordering from issue #61); an
in-page bridge replays both onto the matching channel's socket.

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

- `helpers/mockApi.ts` — in-memory backend stub (`MockApi`) + entity factories
  (`todo`, `list`, `shoppingList/Item`, `recipe`, `note`, `project`,
  `timeEntry`, …). Todos/shopping are seeded via the constructor; recipes,
  notes, projects and time entries via the fluent `seed*` helpers.
- `auth.spec.ts` — login form, validation, error handling, logout.
- `dashboard.spec.ts` — stat tiles (pinned clock), quick-add, "Heute dran"
  check-off, shopping peek, running-timer peek incl. expected end (#31/#142),
  digest preview vs. inbox tile (#76).
- `todos.spec.ts` — inbox rendering, add/plan/complete/delete flows, tab nav.
- `shopping.spec.ts` — list rendering, add/check items, tabs, list CRUD.
- `recipes.spec.ts` — cards, category filter, create/edit/delete, servings
  scaling, add-ingredients-to-shopping.
- `notes.spec.ts` — list/read, search, tag filter, create/edit/delete.
- `time.spec.ts` — projects, start/stop timer, manual entry (+ validation),
  archive, project detail.
