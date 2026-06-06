import { useCallback, useEffect, useRef, useState } from 'react'
import { Icon } from './Icon'

// Shared error-toast for surfacing rejected API writes (`!res.ok`). The backend
// cleanly refuses the mutation (no data loss), but without feedback the action
// would silently not happen — see issues #84 / #96. Mirrors the markup TimeView
// introduced in #91 so all views read consistently.
//
// Usage:
//   const { flashError, errorToast } = useErrorToast()
//   …
//   if (!res.ok) flashError(errorText(await errorCode(res), fallback))
//   …
//   return (<div>… {errorToast}</div>)

const TOAST_MS = 3500

export function useErrorToast() {
  const [message, setMessage] = useState<string | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout>>()

  const flashError = useCallback((msg: string) => {
    clearTimeout(timer.current)
    setMessage(msg)
    timer.current = setTimeout(() => setMessage(null), TOAST_MS)
  }, [])

  // clear the pending timeout if the host component unmounts
  useEffect(() => () => clearTimeout(timer.current), [])

  const errorToast = message ? <ErrorToast message={message} /> : null

  return { flashError, errorToast }
}

export function ErrorToast({ message }: { message: string }) {
  return (
    <div className="hb-toast hb-toast--error" role="alert">
      <Icon name="x" size={18} stroke={2.4} />
      {message}
    </div>
  )
}
