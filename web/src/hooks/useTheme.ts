import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE, safeFetch } from '../api'
import {
  type Theme,
  THEME_PREF_KEY,
  DEFAULT_THEME,
  applyTheme,
  coerceTheme,
  watchSystemTheme,
} from '../ui/theme'

/**
 * Loads the current user's stored UI theme (user_prefs 'theme'), applies it to the
 * document and keeps it in sync — including following the OS live while the choice
 * is 'system'. Returns the choice plus a setter that applies instantly (optimistic)
 * and persists via PUT /user-prefs/theme.
 *
 * Apply-on-load happens here rather than in index.html so it's tied to the logged-in
 * user; until the GET resolves the document keeps its index.html default (light), so
 * there's no flash to dark for light users. The setter's persistence result is
 * surfaced to the caller (the settings selector) so it can show a save error.
 */
export function useTheme(token: string): {
  theme: Theme
  loaded: boolean
  setTheme: (next: Theme) => Promise<boolean>
} {
  const [theme, setThemeState] = useState<Theme>(DEFAULT_THEME)
  const [loaded, setLoaded] = useState(false)
  // Tracks the live OS-watch subscription so a theme change can swap it.
  const unwatch = useRef<() => void>(() => {})

  // (Re)point the system-watch at the active choice and apply immediately.
  const activate = useCallback((next: Theme) => {
    unwatch.current()
    applyTheme(next)
    unwatch.current = watchSystemTheme(next)
  }, [])

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/user-prefs`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.ok) {
        const prefs: Record<string, string> = await result.res.json().catch(() => ({}))
        const stored = coerceTheme(prefs[THEME_PREF_KEY])
        setThemeState(stored)
        activate(stored)
      }
      // On a failed/absent read we keep the index.html default; mark loaded so the
      // selector becomes interactive (a late GET can't clobber a fresh choice).
      setLoaded(true)
    })
    return () => {
      alive = false
      unwatch.current()
    }
  }, [token, activate])

  const setTheme = useCallback(
    async (next: Theme): Promise<boolean> => {
      // Apply optimistically for instant feedback, then persist.
      setThemeState(next)
      activate(next)
      const result = await safeFetch(token, `${API_BASE}/user-prefs/${THEME_PREF_KEY}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: next }),
      })
      return result.ok && result.res.ok
    },
    [token, activate],
  )

  return { theme, loaded, setTheme }
}
