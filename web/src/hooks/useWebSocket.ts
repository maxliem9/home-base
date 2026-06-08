import { useEffect, useRef, useCallback } from 'react'

// The JWT is passed as a WebSocket subprotocol (`["bearer", token]`) so it travels in the
// `Sec-WebSocket-Protocol` handshake header instead of the URL query string — keeping the token
// out of server access logs and browser history. The backend reads it from that header
// (see backend/src/main/kotlin/com/homebase/plugins/Authentication.kt).
export function useWebSocket(target: { url: string; token?: string }, onMessage: (data: string) => void) {
  const { url, token } = target
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  const connect = useCallback((activeRef: { current: boolean }) => {
    if (!activeRef.current) return null
    const ws = token ? new WebSocket(url, ['bearer', token]) : new WebSocket(url)
    wsRef.current = ws

    ws.onmessage = (e) => onMessageRef.current(e.data)
    ws.onclose = () => {
      if (activeRef.current) {
        reconnectRef.current = setTimeout(() => connect(activeRef), 3000)
      }
    }

    return ws
  }, [url, token])

  useEffect(() => {
    const activeRef = { current: true }
    connect(activeRef)
    return () => {
      activeRef.current = false
      if (reconnectRef.current) {
        clearTimeout(reconnectRef.current)
      }
      if (wsRef.current) {
        wsRef.current.onclose = null
        wsRef.current.close()
      }
    }
  }, [connect])
}
