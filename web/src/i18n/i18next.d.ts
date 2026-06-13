// Type-level wiring so i18next validates translation keys against the German
// catalog (the source of truth). This makes `t('section.key')` a COMPILE-TIME
// completeness gate: a missing/renamed key, or a leftover `t.section.key`
// member-access site, fails `tsc`. See i18n/index.ts.
import 'i18next'
import type { de } from './de'

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation'
    resources: {
      translation: typeof de
    }
    // Catalogs intentionally keep nested objects (e.g. recipes.categories,
    // time.weekdays array). Allow returning them via `{ returnObjects: true }`.
    returnNull: false
  }
}
