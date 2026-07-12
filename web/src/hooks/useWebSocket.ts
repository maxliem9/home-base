import { useEffect, useRef } from 'react'
import { subscribeWs } from './wsConnectionManager'

// The JWT is passed as a WebSocket subprotocol (`["bearer", token]`) so it travels in the
// `Sec-WebSocket-Protocol` handshake header instead of the URL query string — keeping the token
// out of server access logs and browser history. The backend reads it from that header
// (see backend/src/main/kotlin/com/homebase/plugins/Authentication.kt).
// `onOpen` fires on every (re)connect — a reliable "the server is reachable again"
// signal that callers use to flush work queued while offline (e.g. the shopping
// view's pending check-offs). It's a better trigger than the window `online` event,
// which doesn't fire for "connected to wifi but no internet" (a store's flaky AP).
//
// The actual socket is shared per `(url, token)` by the connection manager (#551): many
// views can subscribe to the same channel while only one WebSocket exists. This hook is just
// the React glue — it subscribes on mount and releases its ref on unmount. Callbacks are held in
// refs so a re-render doesn't churn the subscription (the identity that matters is url+token).
export function useWebSocket(
  target: { url: string; token?: string },
  onMessage: (data: string) => void,
  onOpen?: () => void,
) {
  const { url, token } = target
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage
  const onOpenRef = useRef(onOpen)
  onOpenRef.current = onOpen

  useEffect(() => {
    const unsubscribe = subscribeWs(url, token, {
      onMessage: (data) => onMessageRef.current(data),
      onOpen: () => onOpenRef.current?.(),
    })
    return unsubscribe
  }, [url, token])
}
