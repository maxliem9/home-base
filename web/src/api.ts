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
