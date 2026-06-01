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

export function withWsToken(url: string, token: string) {
  const wsUrl = new URL(url, window.location.href)
  wsUrl.searchParams.set('token', token)
  return wsUrl.toString()
}
