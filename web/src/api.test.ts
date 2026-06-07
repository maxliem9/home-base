import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorCode, safeFetch } from './api'
import { errorText, t } from './i18n'

// Build a minimal Response stand-in whose .json() resolves/rejects like fetch's.
function res(body: unknown, { reject = false } = {}): Response {
  return {
    json: () => (reject ? Promise.reject(new Error('not json')) : Promise.resolve(body)),
  } as unknown as Response
}

describe('errorCode', () => {
  it('returns the backend ErrorResponse code when present', async () => {
    expect(await errorCode(res({ code: 'PROJECT_ARCHIVED', message: 'Project is archived' }))).toBe('PROJECT_ARCHIVED')
  })

  it('returns null when the body has no usable code', async () => {
    expect(await errorCode(res({ message: 'oops' }))).toBeNull()
    expect(await errorCode(res({ code: '' }))).toBeNull()
    expect(await errorCode(res({ code: 42 }))).toBeNull()
  })

  it('returns null on an empty / non-JSON body', async () => {
    expect(await errorCode(res(null, { reject: true }))).toBeNull()
  })
})

describe('errorText', () => {
  it('maps a known code to its localized message', () => {
    expect(errorText('PROJECT_ARCHIVED', 'fallback')).toBe(t.errors.PROJECT_ARCHIVED)
  })

  it('uses the fallback for unknown or absent codes', () => {
    expect(errorText('NOPE', 'fallback')).toBe('fallback')
    expect(errorText(null, 'fallback')).toBe('fallback')
    expect(errorText(undefined, 'fallback')).toBe('fallback')
  })
})

describe('safeFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns the Response when the fetch resolves (any HTTP status)', async () => {
    const response = { ok: false, status: 409 } as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const result = await safeFetch('tok', '/x', { method: 'POST' })
    expect(result.ok).toBe(true)
    // an HTTP error response is still ok:true — the caller inspects res.ok itself
    if (result.ok) expect(result.res).toBe(response)
  })

  it('catches a rejected fetch (transport error) instead of throwing', async () => {
    const error = new TypeError('Failed to fetch')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(error))
    const result = await safeFetch('tok', '/x')
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toBe(error)
  })

  it('adds the Authorization header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true } as Response)
    vi.stubGlobal('fetch', fetchMock)
    await safeFetch('mytoken', '/x')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer mytoken')
  })
})
