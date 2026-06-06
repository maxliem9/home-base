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

// Pull a user-facing message out of a failed response. The backend reports
// errors as ErrorResponse { code, message } (backend model/Models.kt); we prefer
// that message and fall back to the given default when the body is empty or not
// the expected shape. Lets views surface write failures consistently — see #84.
export async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: unknown }
    if (typeof body?.message === 'string' && body.message.trim()) return body.message
  } catch {
    // empty body or not JSON — fall through to the default
  }
  return fallback
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
