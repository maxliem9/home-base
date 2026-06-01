import { useEffect, useRef, useCallback } from 'react'

export function useWebSocket(url: string, onMessage: (data: string) => void) {
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  const connect = useCallback((activeRef: { current: boolean }) => {
    if (!activeRef.current) return null
    const ws = new WebSocket(url)
    wsRef.current = ws

    ws.onmessage = (e) => onMessageRef.current(e.data)
    ws.onclose = () => {
      if (activeRef.current) {
        reconnectRef.current = setTimeout(() => connect(activeRef), 3000)
      }
    }

    return ws
  }, [url])

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
