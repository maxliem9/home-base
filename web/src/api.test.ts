import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorCode, notifyTransportError, onTransportError, safeFetch } from './api'
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
    expect(errorText('PROJECT_ARCHIVED', 'fallback')).toBe(t('errors.PROJECT_ARCHIVED'))
  })

  it('uses the fallback for unknown or absent codes', () => {
    expect(errorText('NOPE', 'fallback')).toBe('fallback')
    // transport rejects (safeFetch ok:false) pass null → German per-action fallback
    expect(errorText(null, 'fallback')).toBe('fallback')
    expect(errorText(undefined, 'fallback')).toBe('fallback')
  })

  // The recipes "add to shopping" write path (issue #96) routes real failures
  // through the error toast with this per-action German fallback.
  it('exposes the recipes add-to-list write-error fallback', () => {
    expect(t('recipes.addToListFailed')).toBeTruthy()
    expect(errorText(null, t('recipes.addToListFailed'))).toBe(t('recipes.addToListFailed'))
  })

  // The domain views added in issue #96 surface these backend codes; every one
  // must resolve to a German string (and never leak the English fallback).
  it('maps the per-domain write-error codes from issue #96', () => {
    const codes = [
      // todos / lists
      'INVALID_TODO', 'INVALID_STATUS', 'INVALID_PRIORITY', 'INVALID_DUE_DATE',
      'INVALID_RECURRENCE', 'INVALID_SUBTASK', 'INVALID_LIST', 'INVALID_VISIBILITY',
      // shopping
      'INVALID_SHOPPING_ITEM',
      // notes
      'INVALID_NOTE', 'VISIBILITY_FORBIDDEN', 'IMAGE_TOO_LARGE', 'UNSUPPORTED_TYPE',
      'EMPTY_IMAGE', 'NO_IMAGE',
      // recipes
      'INVALID_RECIPE', 'INVALID_INGREDIENT', 'INVALID_CATEGORY',
      // abwesenheit
      'INVALID_TYPE', 'INVALID_HALF', 'INVALID_WEEKDAY', 'INVALID_STATE', 'INVALID_YEAR',
      'DATE_CONFLICT', 'RANGE_TOO_LARGE', 'TOO_MANY_DATES',
      // shared
      'FORBIDDEN', 'MISSING_PARAM',
    ]
    for (const code of codes) {
      expect(t(`errors.${code}`), `missing German text for ${code}`).toBeTruthy()
      expect(errorText(code, 'FALLBACK'), `${code} fell through to fallback`).toBe(t(`errors.${code}`))
    }
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

  // safeFetch must NOT auto-fire the global notifier: write paths show their own
  // per-action toast on a transport reject, and double-toasting would be wrong.
  // The notifier only fires when a read-path caller explicitly calls it (#93).
  it('does NOT fire the global transport notifier on a reject', async () => {
    const listener = vi.fn()
    const unsubscribe = onTransportError(listener)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const result = await safeFetch('tok', '/x')
    expect(result.ok).toBe(false)
    expect(listener).not.toHaveBeenCalled()
    unsubscribe()
  })
})

describe('transport notifier', () => {
  it('notifies every subscribed listener', () => {
    const a = vi.fn()
    const b = vi.fn()
    const offA = onTransportError(a)
    const offB = onTransportError(b)
    notifyTransportError()
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)
    offA()
    offB()
  })

  it('stops notifying after unsubscribe', () => {
    const listener = vi.fn()
    const unsubscribe = onTransportError(listener)
    notifyTransportError()
    unsubscribe()
    notifyTransportError()
    expect(listener).toHaveBeenCalledTimes(1)
  })

  // A read-path caller wires safeFetch's transport reject to the notifier; verify
  // that explicit wiring reaches every listener (mirrors fetchState / fetchAll).
  it('fires once when a read path forwards a safeFetch transport reject', async () => {
    const listener = vi.fn()
    const unsubscribe = onTransportError(listener)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const result = await safeFetch('tok', '/notes')
    if (!result.ok) notifyTransportError()
    expect(listener).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
    unsubscribe()
  })
})
