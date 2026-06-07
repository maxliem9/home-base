export const API_BASE = '/api/v1'

export interface LoginResponse {
  token: string
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!res.ok) {
    throw new Error('Login fehlgeschlagen')
  }
  return res.json()
}

export function authFetch(token: string, input: RequestInfo | URL, init: RequestInit = {}) {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${token}`)
  return fetch(input, { ...init, headers })
}

// Result of safeFetch: either the fetch resolved to a Response (any HTTP
// status, including errors — inspect `res.ok` / `res.status` as usual), or the
// fetch itself REJECTED — a transport error (offline, DNS, aborted connection).
// `ok: false` here means "no response at all", distinct from an HTTP error
// response (which is `ok: true` + a non-2xx `res`). See issue #93.
export type FetchResult =
  | { ok: true; res: Response }
  | { ok: false; error: unknown }

// authFetch that can't reject. #84/#91 handle HTTP error responses (!res.ok),
// but a rejected fetch in an onClick handler became an unhandled rejection and
// the action silently failed. This wrapper centralizes the try/catch so every
// write path can show feedback instead. Reusable by all views (issue #93).
export async function safeFetch(token: string, input: RequestInfo | URL, init: RequestInit = {}): Promise<FetchResult> {
  try {
    return { ok: true, res: await authFetch(token, input, init) }
  } catch (error) {
    // transport failure — no Response exists; caller surfaces a generic message
    return { ok: false, error }
  }
}

// Global transport-error notifier (issue #93). Write paths already show a
// per-action toast on a transport reject, so they DON'T use this — that would
// double-toast. It exists for the background GET/read paths (initial loads,
// refreshes, CSV export) which have no per-action message: on a transport
// reject they fire this once and a single global TransportErrorToast appears.
// safeFetch deliberately does NOT fire it automatically; callers decide.
const transportListeners = new Set<() => void>()

export function onTransportError(listener: () => void): () => void {
  transportListeners.add(listener)
  return () => {
    transportListeners.delete(listener)
  }
}

export function notifyTransportError(): void {
  transportListeners.forEach((l) => l())
}

// Read the `code` off a failed response. The backend reports errors as
// ErrorResponse { code, message } (backend model/Models.kt); we surface the
// stable code rather than the English `message` so the UI can show a localized
// string (see i18n `errorText`). Returns null when the body is empty / not the
// expected shape. Lets views surface write failures consistently — see #84.
export async function errorCode(res: Response): Promise<string | null> {
  try {
    const body = (await res.json()) as { code?: unknown }
    if (typeof body?.code === 'string' && body.code) return body.code
  } catch {
    // empty body or not JSON — no code available
  }
  return null
}

export function withWsToken(url: string, token: string) {
  const wsUrl = new URL(url, window.location.href)
  wsUrl.searchParams.set('token', token)
  return wsUrl.toString()
}

// <img> tags can't send an Authorization header, so the image endpoint accepts the
// JWT via the same `?token=` fallback used for WebSocket upgrades.
export function noteImageUrl(noteId: string, imageId: string, token: string) {
  return `${API_BASE}/notes/${noteId}/images/${imageId}?token=${encodeURIComponent(token)}`
}
