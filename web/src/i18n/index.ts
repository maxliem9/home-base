import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { de } from './de'
import { en } from './en'

// The shape every locale catalog must implement. Derived from the German
// catalog, which is the source of truth. `en.ts` is typed `Messages` so `tsc`
// enforces structural parity (no missing/extra key).
export type Messages = typeof de

// Persisted language preference. German is the default (keeps the existing UI and
// e2e green); English is opt-in via the settings switcher.
export const LANG_STORAGE_KEY = 'homebase_lang'
const SUPPORTED = ['de', 'en'] as const
export type Lang = (typeof SUPPORTED)[number]

function initialLang(): Lang {
  try {
    const saved = localStorage.getItem(LANG_STORAGE_KEY)
    if (saved === 'de' || saved === 'en') return saved
  } catch {
    // localStorage unavailable (private mode / SSR-ish) — fall back to default.
  }
  return 'de'
}

// One i18next instance for the whole app. The catalogs keep their nested object
// shape (no flattening): each locale's whole `Messages` object is the single
// `translation` namespace, so keys read as `section.key` (e.g. `todos.addFailed`).
// Interpolation uses SINGLE braces (`{name}`) to match the existing placeholders
// in the catalogs unchanged.
void i18next.use(initReactI18next).init({
  resources: {
    de: { translation: de },
    en: { translation: en },
  },
  lng: initialLang(),
  fallbackLng: 'de',
  supportedLngs: SUPPORTED,
  interpolation: {
    escapeValue: false, // React already escapes; values are plain UI strings.
    prefix: '{',
    suffix: '}',
  },
  returnNull: false,
})

// Keep the document's lang attribute in sync with the active language so screen
// readers / browser tooling announce the right language (issue #208). i18next is
// bootstrapped synchronously above, so reading the language here is safe; the
// listener then follows every later switch. Guarded for non-DOM environments —
// vitest unit tests run in node (no `document`), and this module is imported there.
if (typeof document !== 'undefined') {
  document.documentElement.lang = i18next.resolvedLanguage ?? initialLang()
  i18next.on('languageChanged', (lng) => {
    document.documentElement.lang = lng
  })
}

/** Change the active language and persist the choice. Consumers re-render. */
export function setLang(lang: Lang): void {
  try {
    localStorage.setItem(LANG_STORAGE_KEY, lang)
  } catch {
    // best-effort persistence; the in-memory switch still applies this session.
  }
  void i18next.changeLanguage(lang)
}

/** The currently active language. */
export function currentLang(): Lang {
  return (i18next.resolvedLanguage as Lang) || 'de'
}

// Map a backend ErrorResponse `code` (see api `errorCode`) to a localized
// message, falling back to the given per-action default for missing/unknown
// codes. Shared by all views so write failures read consistently — issue #84.
// Uses the global i18next instance so non-component (module-scope) callers work
// too; the active language is read live.
export function errorText(code: string | null | undefined, fallback: string): string {
  if (!code) return fallback
  // `errors` is a flat string map; the key is `errors.<CODE>`. `defaultValue: ''`
  // makes an unknown code resolve to empty (falsy) → fall back to the per-action
  // default, matching the previous `(code && t.errors[code]) || fallback`.
  return i18next.t(`errors.${code}`, { defaultValue: '' }) || fallback
}

// Re-export the global translator for module-scope (non-React) callers and tests.
// Inside components prefer `const { t } = useTranslation()` so they re-render on
// a language change; this `t` is a snapshot bound to the current language.
export const t = i18next.t.bind(i18next)
