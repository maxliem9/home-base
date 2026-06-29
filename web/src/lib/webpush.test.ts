// Pure-logic guards for the Web Push helpers (#429 Phase 2b). The subscribe/enable flow itself
// needs a real browser (service workers + Push API) and is verified manually / by hand — see the
// PR notes. Here we only cover the capability gate, which must NOT throw in a non-browser context
// and must report "unsupported" so the UI hides the control. Vitest runs in a plain Node env, so
// these globals are absent — exactly the unsupported case.
import { describe, expect, it } from 'vitest'
import { pushSupported } from './webpush'

describe('pushSupported', () => {
  it('returns false (and does not throw) when service workers / Push API are unavailable', () => {
    // In the Node test environment there is no `navigator.serviceWorker` / `window.PushManager`.
    expect(pushSupported()).toBe(false)
  })
})
