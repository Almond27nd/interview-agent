/**
 */

import { useEffect, useRef } from 'react'
import { WSClient } from '../api/ws'
import { useChatStore } from '../store/chatStore'
import { useAuthStore } from '../store/authStore'

export function useWebSocket() {
  const clientRef = useRef<WSClient | null>(null)
  const handleServerMessage = useChatStore((s) => s.handleServerMessage)
  const setConnected = useChatStore((s) => s.setConnected)
  const token = useAuthStore((s) => s.token)

  useEffect(() => {
    if (!token) {
      // 未登录，不建立连接
      clientRef.current?.disconnect()
      clientRef.current = null
      return
    }

    const client = new WSClient(
      (msg) => handleServerMessage(msg),
      (connected) => setConnected(connected),
    )
    client.connect(token)
    clientRef.current = client

    return () => client.disconnect()
  }, [handleServerMessage, setConnected, token])

  return clientRef
}
