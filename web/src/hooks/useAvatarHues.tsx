import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { API_BASE, safeFetch } from '../api'
import type { User } from '../types'

// Per-user avatar-hue overrides (Teil von #100), loaded once from the household-visible
// roster (GET /users, which carries avatarHue) and shared app-wide via context. This is
// the canonical place the Avatar component reads a stored hue from, so a colour chosen in
// Einstellungen → Konto shows up at EVERY avatar render site (assignees, the timer, the
// calendar, …) without threading a prop through each one.
//
// Why a context rather than user_prefs: avatar colour is household-visible — your partner
// must see your colour — so it lives on the shared roster, not the own-read-only prefs.
//
// Partner propagation: there is no roster WebSocket, so a colour the partner picks reaches
// you on the next roster fetch — on mount and on window focus (a returning tab re-reads).
// Your OWN change is applied optimistically here and re-fetched immediately, so your UI
// updates instantly. (A dedicated WS broadcast would make it real-time — possible later.)

type HueMap = Record<string, number | null | undefined>

interface AvatarHuesValue {
  /** Stored hue override for a username (null/undefined = automatic/derived). */
  hueOf: (username?: string | null) => number | null | undefined
  /** Optimistically set the current user's hue locally + persist via the API. */
  setMyColor: (username: string, hue: number | null) => Promise<boolean>
}

const AvatarHuesContext = createContext<AvatarHuesValue>({
  hueOf: () => undefined,
  setMyColor: async () => false,
})

export function AvatarHuesProvider({ token, children }: { token: string; children: ReactNode }) {
  const [hues, setHues] = useState<HueMap>({})
  // Guards a late GET from clobbering a fresh local optimistic write: once the user has
  // picked their own colour we keep that value even if an in-flight roster read predates it.
  const myOverride = useRef<{ username: string; hue: number | null } | null>(null)

  const load = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/users`)
    if (!result.ok || !result.res.ok) return
    try {
      const data: User[] = await result.res.json()
      if (!Array.isArray(data)) return
      const next: HueMap = {}
      for (const u of data) if (u?.username) next[u.username] = u.avatarHue ?? null
      // Keep any optimistic local write that the server read hasn't caught up to yet.
      const mine = myOverride.current
      if (mine) next[mine.username] = mine.hue
      setHues(next)
    } catch {
      // keep the current map on a malformed body
    }
  }, [token])

  useEffect(() => {
    let alive = true
    load().catch(() => {})
    // A returning tab re-reads the roster, so a colour the partner changed while we were
    // away shows up. Cheap (one small GET) and only on actual focus.
    const onFocus = () => {
      if (alive) load().catch(() => {})
    }
    window.addEventListener('focus', onFocus)
    return () => {
      alive = false
      window.removeEventListener('focus', onFocus)
    }
  }, [load])

  const setMyColor = useCallback(
    async (username: string, hue: number | null): Promise<boolean> => {
      // Apply optimistically for instant feedback, remember it so a racing GET can't undo it.
      myOverride.current = { username, hue }
      setHues((prev) => ({ ...prev, [username]: hue }))
      const result = await safeFetch(token, `${API_BASE}/users/me/avatar-color`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hue }),
      })
      const ok = result.ok && result.res.ok
      // Re-sync from the server (also normalises if the write was rejected).
      load().catch(() => {})
      return ok
    },
    [token, load],
  )

  const hueOf = useCallback((username?: string | null) => (username ? hues[username] : undefined), [hues])

  return <AvatarHuesContext.Provider value={{ hueOf, setMyColor }}>{children}</AvatarHuesContext.Provider>
}

export function useAvatarHues(): AvatarHuesValue {
  return useContext(AvatarHuesContext)
}
