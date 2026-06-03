import { de } from './de'

// The shape every locale catalog must implement. Derived from the German
// catalog, which is the source of truth.
export type Messages = typeof de

// Active locale. There is no language switcher yet — the app is German-only.
// To add one later:
//   1. Add `en.ts` (etc.) exporting `const en: Messages = { … }`.
//   2. Build a `{ de, en } as Record<string, Messages>` map.
//   3. Replace this constant with React state/context that picks from the map
//      based on the user's saved preference, and expose it via a hook.
// Components already read everything through `t`, so only this file changes.
export const t: Messages = de
