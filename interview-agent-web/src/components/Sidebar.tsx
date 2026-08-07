/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useState } from 'react'
import { useChatStore } from '../store/chatStore'
import { useAuthStore } from '../store/authStore'
import { HistoryModal } from './HistoryModal'

export function Sidebar() {
  const { clearMessages, connected } = useChatStore()
  const username = useAuthStore((s) => s.username)
  const logout = useAuthStore((s) => s.logout)
  const [showHistory, setShowHistory] = useState(false)

  return (
    <div className="w-64 bg-gray-900 text-white flex flex-col h-screen">
      <div className="p-4 border-b border-gray-700">
        <h1 className="text-lg font-bold">InterviewAgent</h1>
        <p className="text-xs text-gray-400 mt-1">AI 模拟面试系统</p>
      </div>

      <div className="p-3">
        <button
          onClick={clearMessages}
          className="w-full py-2 px-4 border border-gray-600 rounded-lg text-sm hover:bg-gray-800 transition"
        >
          + 新建对话
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-3">
        <button
          onClick={() => setShowHistory(true)}
          className="w-full text-left text-sm text-gray-300 hover:text-white hover:bg-gray-800 rounded-lg px-2 py-2 transition"
        >
          📋 历史面试记录
        </button>
      </div>

      {showHistory && <HistoryModal onClose={() => setShowHistory(false)} />}

      <div className="p-3 border-t border-gray-700 space-y-2">
        <div className="flex items-center gap-2 text-xs">
          <span className={`w-2 h-2 rounded-full ${connected ? 'bg-green-500' : 'bg-red-500'}`} />
          <span className="text-gray-400">{connected ? '已连接' : '未连接'}</span>
        </div>
        {username && (
          <div className="flex items-center justify-between text-xs">
            <span className="text-gray-300">{username}</span>
            <button
              onClick={logout}
              className="text-gray-500 hover:text-red-400 transition"
            >
              退出
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
