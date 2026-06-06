import { describe, expect, it } from 'vitest'
import { errorMessage } from './api'

// Build a minimal Response stand-in whose .json() resolves/rejects like fetch's.
function res(body: unknown, { reject = false } = {}): Response {
  return {
    json: () => (reject ? Promise.reject(new Error('not json')) : Promise.resolve(body)),
  } as unknown as Response
}

describe('errorMessage', () => {
  it('returns the backend ErrorResponse message when present', async () => {
    const out = await errorMessage(res({ code: 'PROJECT_ARCHIVED', message: 'Project is archived' }), 'fallback')
    expect(out).toBe('Project is archived')
  })

  it('falls back when the body has no usable message', async () => {
    expect(await errorMessage(res({ code: 'X' }), 'fallback')).toBe('fallback')
    expect(await errorMessage(res({ message: '   ' }), 'fallback')).toBe('fallback')
    expect(await errorMessage(res({ message: 42 }), 'fallback')).toBe('fallback')
  })

  it('falls back on an empty / non-JSON body', async () => {
    expect(await errorMessage(res(null, { reject: true }), 'fallback')).toBe('fallback')
  })
})
