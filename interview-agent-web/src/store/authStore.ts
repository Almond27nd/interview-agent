/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { create } from 'zustand'

interface AuthState {
  token: string | null
  username: string | null
  login: (token: string, username: string) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: localStorage.getItem('token'),
  username: localStorage.getItem('username'),

  login: (token, username) => {
    localStorage.setItem('token', token)
    localStorage.setItem('username', username)
    set({ token, username })
  },

  logout: () => {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    set({ token: null, username: null })
  },
}))
