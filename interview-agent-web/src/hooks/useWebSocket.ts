/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
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
