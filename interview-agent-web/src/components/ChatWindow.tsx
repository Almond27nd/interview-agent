/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useEffect, useRef, useState } from 'react'
import { MessageBubble } from './MessageBubble'
import { FileUpload } from './FileUpload'
import { StageIndicator } from './StageIndicator'
import { useChatStore } from '../store/chatStore'
import type { WSClient } from '../api/ws'
import type { ClientMessage } from '../types/message'

interface ChatWindowProps {
  wsRef: React.RefObject<WSClient | null>
}

export function ChatWindow({ wsRef }: ChatWindowProps) {
  const { messages, isInterviewing, upload, lastErrorSeq, questionDeadline } = useChatStore()
  const [input, setInput] = useState('')
  const [remainingSec, setRemainingSec] = useState<number | null>(null)
  const [attachedFile, setAttachedFile] = useState<{ name: string; data: string } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [showInterviewSetup, setShowInterviewSetup] = useState(false)
  const [jdText, setJdText] = useState('')
  const [resumeText, setResumeText] = useState('')
  const [jdFile, setJdFile] = useState<{ name: string; data: string } | null>(null)
  const [resumeFile, setResumeFile] = useState<{ name: string; data: string } | null>(null)
  const questionFileRef = useRef<HTMLInputElement>(null)
  const [uploadingQuestions, setUploadingQuestions] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // 限时答题倒计时：questionDeadline 由 chatStore 在收到 'question' 消息时设置，
  // 真正的超时裁决在后端（poll 超时），这里每秒刷新只是为了给候选人视觉上的时间压力提示。
  useEffect(() => {
    if (!questionDeadline) {
      setRemainingSec(null)
      return
    }
    const tick = () => {
      const left = Math.max(0, Math.ceil((questionDeadline - Date.now()) / 1000))
      setRemainingSec(left)
    }
    tick()
    const timer = window.setInterval(tick, 1000)
    return () => window.clearInterval(timer)
  }, [questionDeadline])

  const send = (msg: ClientMessage) => {
    wsRef.current?.send(msg)
  }

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      const base64 = (reader.result as string).split(',')[1]
      setAttachedFile({ name: file.name, data: base64 })
    }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  const handleSend = () => {
    const text = input.trim()
    if (!text && !attachedFile) return

    if (isInterviewing) {
      send({ type: 'answer', content: text })
    } else if (attachedFile) {
      // 带附件的消息：文件内容 + 文本一起发送
      const content = `[FILE:${attachedFile.name}]${attachedFile.data}` + (text ? `\n${text}` : '')
      send({ type: 'chat', content })
    } else {
      send({ type: 'chat', content: text })
    }

    const displayText = attachedFile
      ? `${attachedFile.name}${text ? '\n' + text : ''}`
      : text
    useChatStore.getState().addMessage({
      id: String(Date.now()),
      role: 'user',
      content: displayText,
      messageType: attachedFile ? 'file' : 'text',
      timestamp: Date.now(),
    })
    setInput('')
    setAttachedFile(null)
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const handleUploadQuestions = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploadingQuestions(true)
    const reader = new FileReader()
    reader.onload = () => {
      const base64 = (reader.result as string).split(',')[1]
      useChatStore.getState().setUploading(file.name)
      send({ type: 'upload_questions', filename: file.name, data: base64 })
      useChatStore.getState().addMessage({
        id: String(Date.now()),
        role: 'user',
        content: `上传题库：${file.name}`,
        messageType: 'file',
        timestamp: Date.now(),
      })
      setUploadingQuestions(false)
    }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  // 后端启动面试前会先做输入质量校验（JD/简历过短等），失败时只会回一条 error 消息，
  // 不会有 stage_change。这里不再乐观地立即置位 isInterviewing / 清空表单 / 关闭面板，
  // 而是等后端真正推进到第一个阶段（isInterviewing 由 store 在收到 stage_change 后置位）
  // 才认为"开始成功"；如果校验失败，表单内容原样保留在面板里，用户改完可直接再点一次重试，
  // 不需要重新填写或重新上传文件。
  const [starting, setStarting] = useState(false)

  useEffect(() => {
    if (isInterviewing && starting) {
      // 面试已被后端真正启动，此时才清空准备面板
      setStarting(false)
      setShowInterviewSetup(false)
      setJdText('')
      setResumeText('')
      setJdFile(null)
      setResumeFile(null)
    }
  }, [isInterviewing, starting])

  useEffect(() => {
    // 提交后若后端返回 error（大概率是启动前的输入质量校验失败），结束"提交中"状态，
    // 让按钮恢复可点、用户可直接修改表单内容后重试。
    if (starting) {
      setStarting(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastErrorSeq])

  const handleStartInterview = () => {
    const jd = jdFile ? `[FILE:${jdFile.name}]${jdFile.data}` : jdText
    const resume = resumeFile ? `[FILE:${resumeFile.name}]${resumeFile.data}` : resumeText
    if (!jd || !resume) return

    setStarting(true)
    send({ type: 'start_interview', jd, resume })
    useChatStore.getState().addMessage({
      id: String(Date.now()),
      role: 'user',
      content: '开始面试',
      messageType: 'text',
      timestamp: Date.now(),
    })
  }

  return (
    <div className="flex-1 flex flex-col h-screen">
      <StageIndicator />

      {/* 消息列表 */}
      <div className="flex-1 overflow-y-auto px-4 py-6">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full text-gray-400">
            <p className="text-lg mb-2">InterviewAgent</p>
            <p className="text-sm">输入消息开始聊天，或点击下方按钮开始模拟面试</p>
          </div>
        )}
        {messages.map((msg) => (
          <MessageBubble key={msg.id} msg={msg} onSend={send} />
        ))}
        <div ref={bottomRef} />
      </div>

      {/* 题库上传状态横幅：独立于聊天流，醒目展示上传/导入进度 */}
      {upload.status !== 'idle' && (
        <div className="px-4 py-2 border-t bg-white">
          {upload.status === 'uploading' && (
            <div className="flex items-center gap-2 text-sm text-blue-700">
              <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8V0C5.4 0 0 5.4 0 12h4z" />
              </svg>
              <span className="font-medium">{upload.stage || '正在处理题库...'}</span>
              <span className="text-xs text-gray-400">（大文件可能需数分钟，请耐心等待）</span>
            </div>
          )}
          {upload.status === 'done' && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-green-50 border border-green-200">
              <span className="text-green-600 text-lg leading-none">✅</span>
              <div className="flex-1 min-w-0">
                <p className="text-sm text-green-800 font-medium whitespace-pre-wrap">{upload.result || '题库导入成功'}</p>
                {upload.detail && (
                  <details className="mt-1">
                    <summary className="text-xs text-gray-500 cursor-pointer hover:text-gray-700">校验失败详情</summary>
                    <pre className="mt-1 text-xs text-red-600 whitespace-pre-wrap">{upload.detail}</pre>
                  </details>
                )}
              </div>
              <button onClick={() => useChatStore.getState().resetUpload()}
                className="text-gray-400 hover:text-gray-600 ml-auto">✕</button>
            </div>
          )}
          {upload.status === 'error' && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-red-50 border border-red-200">
              <span className="text-red-500 text-lg leading-none">⚠️</span>
              <div className="flex-1 min-w-0">
                <p className="text-sm text-red-800 font-medium whitespace-pre-wrap">{upload.result || '题库上传失败'}</p>
                <p className="text-xs text-red-600 mt-1">提示：若为扫描件 PDF，请先用 OCR 转成可复制文本后再上传。</p>
              </div>
              <button onClick={() => useChatStore.getState().resetUpload()}
                className="text-gray-400 hover:text-gray-600 ml-auto">✕</button>
            </div>
          )}
        </div>
      )}

      {/* 面试准备面板 */}
      {showInterviewSetup && (
        <div className="px-4 py-4 border-t bg-gray-50">
          <div className="grid grid-cols-2 gap-4 mb-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">JD（岗位描述）</label>
              <FileUpload
                label="拖拽上传 JD 文件（PDF/TXT/DOCX）或在下方粘贴"
                accept=".pdf,.txt,.docx,.md"
                onFileLoaded={(name, data) => setJdFile({ name, data })}
              />
              <textarea
                value={jdText}
                onChange={(e) => setJdText(e.target.value)}
                placeholder="或粘贴 JD 文本 / 招聘链接..."
                rows={4}
                className="mt-2 w-full border rounded-lg p-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">简历</label>
              <FileUpload
                label="拖拽上传简历（PDF/DOCX）或在下方粘贴"
                accept=".pdf,.txt,.docx"
                onFileLoaded={(name, data) => setResumeFile({ name, data })}
              />
              <textarea
                value={resumeText}
                onChange={(e) => setResumeText(e.target.value)}
                placeholder="或粘贴简历文本..."
                rows={4}
                className="mt-2 w-full border rounded-lg p-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setShowInterviewSetup(false)}
              className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-200 rounded-lg"
            >
              取消
            </button>
            <button
              onClick={handleStartInterview}
              disabled={starting || (!jdText && !jdFile) || (!resumeText && !resumeFile)}
              className="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {starting ? '正在校验...' : '开始面试'}
            </button>
          </div>
        </div>
      )}

      {/* 限时答题倒计时 */}
      {isInterviewing && remainingSec !== null && (
        <div className="px-4 pt-2 flex items-center gap-2">
          <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
            remainingSec <= 20 ? 'bg-red-100 text-red-600' : 'bg-gray-100 text-gray-500'
          }`}>
            ⏱ 剩余 {remainingSec}s
          </span>
          <span className="text-xs text-gray-400">超时未作答将自动记为本题 0 分</span>
        </div>
      )}

      {/* 输入区 */}
      <div className="border-t px-4 py-3">
        {/* 附件预览 */}
        {attachedFile && (
          <div className="flex items-center gap-2 mb-2 px-2 py-1.5 bg-blue-50 border border-blue-200 rounded-lg text-sm">
            <span className="text-blue-700">{attachedFile.name}</span>
            <button
              onClick={() => setAttachedFile(null)}
              className="text-gray-400 hover:text-red-500 ml-auto"
            >
              x
            </button>
          </div>
        )}
        <div className="flex items-end gap-2">
          {!isInterviewing && (
            <>
              <button
                onClick={() => setShowInterviewSetup(!showInterviewSetup)}
                className="px-3 py-2 text-sm bg-green-600 text-white rounded-lg hover:bg-green-700 whitespace-nowrap"
              >
                开始面试
              </button>
              <input
                ref={questionFileRef}
                type="file"
                accept=".pdf,.txt,.md,.docx"
                className="hidden"
                onChange={handleUploadQuestions}
              />
              <div className="relative group">
                <button
                  onClick={() => questionFileRef.current?.click()}
                  disabled={uploadingQuestions}
                  className="px-3 py-2 text-sm bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 whitespace-nowrap"
                >
                  {uploadingQuestions ? '解析中...' : '上传题库'}
                </button>
                <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-64 px-3 py-2 bg-gray-800 text-white text-xs rounded-lg opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-50">
                  <p className="font-medium mb-1">上传自定义面试题库</p>
                  <p>支持 PDF/TXT/MD 格式，系统自动解析入库。</p>
                  <p className="mt-1 text-gray-300">• 不同文件名 → 追加到知识库</p>
                  <p className="text-gray-300">• 同文件名重传 → 自动更新该题库</p>
                  <p className="text-gray-300">• 相同文件内容 → 自动跳过</p>
                  <div className="absolute top-full left-1/2 -translate-x-1/2 border-4 border-transparent border-t-gray-800" />
                </div>
              </div>
            </>
          )}
          {isInterviewing && (
            <button
              onClick={() => send({ type: 'quit_interview' })}
              className="px-3 py-2 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600 whitespace-nowrap"
            >
              终止面试
            </button>
          )}
          {/* 文件上传按钮 */}
          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,.txt,.docx,.md"
            className="hidden"
            onChange={handleFileSelect}
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            className="p-2 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition"
            title="上传文件"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
            </svg>
          </button>
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={isInterviewing ? '输入你的回答...' : '输入消息、粘贴链接或上传文件...'}
            rows={1}
            className="flex-1 border rounded-xl px-4 py-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() && !attachedFile}
            className="px-4 py-2 bg-blue-600 text-white rounded-xl hover:bg-blue-700 disabled:opacity-50"
          >
            发送
          </button>
        </div>
      </div>
    </div>
  )
}