import { useEffect, useRef, useState } from 'react'
import { onTransportError } from '../api'
import { t } from '../i18n'
import { Icon } from './Icon'

// Global transport-error toast (issue #93). Mounted ONCE in App's Shell, it
// listens for transport rejects fired by the background GET/read paths (initial
// loads, refreshes, CSV export) which — unlike the write paths — have no
// per-action message. Those callers fire `notifyTransportError()` explicitly;
// the write paths keep showing their own per-action toast and never trigger
// this, so there's no double-toast.

const TOAST_MS = 3500

export function TransportErrorToast() {
  const [visible, setVisible] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout>>()

  useEffect(() => {
    const unsubscribe = onTransportError(() => {
      clearTimeout(timer.current)
      setVisible(true)
      timer.current = setTimeout(() => setVisible(false), TOAST_MS)
    })
    // StrictMode double-invokes effects: unsubscribe AND clear the pending
    // auto-dismiss timer so a remount can't leave a stale timeout running.
    return () => {
      unsubscribe()
      clearTimeout(timer.current)
    }
  }, [])

  if (!visible) return null
  return (
    <div className="hb-toast hb-toast--error" role="alert">
      <Icon name="x" size={18} stroke={2.4} />
      {t.common.networkError}
    </div>
  )
}
