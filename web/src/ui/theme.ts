// UI theme (#100, Phase 2). A per-user preference (key 'theme' in user_prefs)
// drives the `data-theme` attribute on <html>, which the design tokens in
// index.css already switch on (`[data-theme="dark"]`). No Tailwind `dark:`
// variant is used — the whole palette is CSS-variable based.
//
// 'system' follows the OS via prefers-color-scheme and keeps following it live.
// The stored value is the user's choice ('light' | 'dark' | 'system'); the
// attribute we actually set is always the *resolved* 'light' | 'dark'.

export type Theme = 'light' | 'dark' | 'system'

export const THEME_PREF_KEY = 'theme'
export const DEFAULT_THEME: Theme = 'system'

const SYSTEM_DARK = '(prefers-color-scheme: dark)'

/** Coerce an arbitrary stored string to a known Theme (unknown → default). */
export function coerceTheme(value: string | null | undefined): Theme {
  return value === 'light' || value === 'dark' || value === 'system' ? value : DEFAULT_THEME
}

/** Whether the OS currently prefers dark. Safe when matchMedia is unavailable (SSR/old). */
function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(SYSTEM_DARK).matches
}

/** The concrete light/dark a given choice resolves to right now. */
export function resolveTheme(theme: Theme): 'light' | 'dark' {
  if (theme === 'system') return systemPrefersDark() ? 'dark' : 'light'
  return theme
}

/** Write the resolved theme onto <html data-theme> (what index.css reads). */
export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', resolveTheme(theme))
}

/**
 * Keep the document in sync with the OS while the choice is 'system'. Returns an
 * unsubscribe fn. For 'light'/'dark' there is nothing to watch, so it's a no-op.
 * Re-call this whenever the stored choice changes (the caller owns the lifecycle).
 */
export function watchSystemTheme(theme: Theme): () => void {
  if (theme !== 'system' || typeof window === 'undefined' || !window.matchMedia) return () => {}
  const mq = window.matchMedia(SYSTEM_DARK)
  const onChange = () => applyTheme('system')
  mq.addEventListener('change', onChange)
  return () => mq.removeEventListener('change', onChange)
}
