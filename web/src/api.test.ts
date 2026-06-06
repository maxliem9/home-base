import { describe, expect, it } from 'vitest'
import { errorCode } from './api'
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
