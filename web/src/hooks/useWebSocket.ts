import { useEffect, useRef, useCallback } from 'react'

export function useWebSocket(url: string, onMessage: (data: string) => void) {
  const wsRef = useRef<WebSocket | null>(null)
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  const connect = useCallback(() => {
    const ws = new WebSocket(url)
    wsRef.current = ws

    ws.onmessage = (e) => onMessageRef.current(e.data)
    ws.onclose = () => {
      setTimeout(connect, 3000)
    }

    return ws
  }, [url])

  useEffect(() => {
    const ws = connect()
    return () => {
      ws.onclose = null
      ws.close()
    }
  }, [connect])
}
