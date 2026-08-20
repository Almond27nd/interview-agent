/**
 */

import { Sidebar } from './components/Sidebar'
import { ChatWindow } from './components/ChatWindow'
import { LoginPage } from './components/LoginPage'
import { useWebSocket } from './hooks/useWebSocket'
import { useAuthStore } from './store/authStore'

export default function App() {
  const token = useAuthStore((s) => s.token)
  const wsRef = useWebSocket()

  if (!token) {
    return <LoginPage />
  }

  return (
    <div className="flex h-screen bg-white">
      <Sidebar />
      <ChatWindow wsRef={wsRef} />
    </div>
  )
}
