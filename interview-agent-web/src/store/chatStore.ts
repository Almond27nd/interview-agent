/**
 */

import { create } from 'zustand'
import type { ChatMessage, ServerMessage } from '../types/message'

interface UploadState {
  status: 'idle' | 'uploading' | 'done' | 'error'
  stage: string          // 当前阶段文案
  result: string         // 导入结果文案
  detail: string | null  // 校验失败详情
}

/**
 * 限时答题倒计时时长（秒），需与后端 app.interview.answer-timeout-seconds 保持一致
 * （后端默认 120 秒）。前端只用它渲染倒计时 UI，真正的超时裁决由后端 poll() 超时决定，
 * 这里即使两端配置临时不一致，也只是倒计时显示不够精确，不会导致误判。
 */
export const ANSWER_TIMEOUT_SECONDS = 120

interface ChatState {
  messages: ChatMessage[]
  connected: boolean
  isInterviewing: boolean
  currentStage: string
  upload: UploadState
  /** 最近一次收到 error 消息的自增序号，供 UI（如面试准备面板）监听以结束"提交中"状态 */
  lastErrorSeq: number
  /** 当前题目的作答截止时间点（epoch ms），null 表示当前没有需要倒计时的题目 */
  questionDeadline: number | null

  addMessage: (msg: ChatMessage) => void
  setConnected: (v: boolean) => void
  setInterviewing: (v: boolean) => void
  handleServerMessage: (msg: ServerMessage) => void
  clearMessages: () => void
  setUploading: (filename: string) => void
  resetUpload: () => void
}

let msgId = 0
const nextId = () => String(++msgId)

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  connected: false,
  isInterviewing: false,
  currentStage: '',
  upload: { status: 'idle', stage: '', result: '', detail: null },
  lastErrorSeq: 0,
  questionDeadline: null,

  addMessage: (msg) => set((s) => ({ messages: [...s.messages, msg] })),

  setConnected: (connected) => set({ connected }),

  setInterviewing: (v) => set({ isInterviewing: v }),

  clearMessages: () => set({ messages: [], upload: { status: 'idle', stage: '', result: '', detail: null } }),

  setUploading: (filename: string) =>
    set({ upload: { status: 'uploading', stage: `正在上传并解析 ${filename}...`, result: '', detail: null } }),

  resetUpload: () => set({ upload: { status: 'idle', stage: '', result: '', detail: null } }),

  handleServerMessage: (msg) => {
    const now = Date.now()
    switch (msg.type) {
      case 'chat_reply':
        get().addMessage({
          id: nextId(), role: 'assistant', content: msg.content,
          messageType: 'text', timestamp: now,
        })
        break

      case 'stage_change': {
        const isUploadStage = !!msg.stage && msg.stage.startsWith('upload_')
        // 面试是否真正开始，以后端第一个非上传类 stage_change（jd_analysis）为准，
        // 而不是前端点击按钮时就乐观置位。这样如果后端在启动前校验失败（JD/简历质量不足），
        // isInterviewing 永远不会被置为 true，用户能留在准备面板里直接修改后重试，
        // 不会出现"UI 显示面试中，但后端其实从未开始"的状态不一致。
        set((s) => ({
          currentStage: msg.stage,
          isInterviewing: isUploadStage ? s.isInterviewing : true,
        }))
        // 上传相关阶段单独维护到 upload 状态，并在聊天区保留系统气泡
        if (isUploadStage) {
          set((s) => ({
            upload: { ...s.upload, status: 'uploading', stage: msg.message },
          }))
        }
        get().addMessage({
          id: nextId(), role: 'system', content: msg.message,
          messageType: 'stage', stage: msg.stage, timestamp: now,
        })
        break
      }

      case 'question_delta': {
        // 流式增量：若最后一条消息正是同一题号且仍在流式生成中，直接追加；否则新开一条流式题目消息。
        set((s) => {
          const messages = [...s.messages]
          const last = messages[messages.length - 1]
          if (last && last.messageType === 'question' && last.streaming && last.questionNum === msg.question_num) {
            messages[messages.length - 1] = { ...last, content: last.content + msg.content }
            return { messages }
          }
          messages.push({
            id: nextId(), role: 'assistant', content: msg.content,
            messageType: 'question', questionNum: msg.question_num, timestamp: now,
            streaming: true,
          })
          return { messages }
        })
        break
      }

      case 'question': {
        // 完整文本收尾：若正好是同一题号的流式消息在收尾，直接替换为完整内容（可能带来源标注后缀）
        // 并结束流式状态；否则（如低分巩固题目，没有经过流式增量）直接追加一条新消息。
        set((s) => {
          const messages = [...s.messages]
          const last = messages[messages.length - 1]
          if (last && last.messageType === 'question' && last.streaming && last.questionNum === msg.question_num) {
            messages[messages.length - 1] = { ...last, content: msg.content, streaming: false }
          } else {
            messages.push({
              id: nextId(), role: 'assistant', content: msg.content,
              messageType: 'question', questionNum: msg.question_num, timestamp: now,
            })
          }
          return { messages }
        })
        // 开始本题倒计时；后端才是超时的最终裁决方，这里只用于 UI 展示
        set({ questionDeadline: now + ANSWER_TIMEOUT_SECONDS * 1000 })
        break
      }

      case 'score':
        get().addMessage({
          id: nextId(), role: 'system', content: msg.feedback,
          messageType: 'score', score: msg.score, feedback: msg.feedback,
          keyPointsHit: msg.key_points_hit, keyPointsMissed: msg.key_points_missed,
          timestamp: now,
        })
        // 已收到本题评分，说明作答已被处理（或已因超时被后端裁定），清除倒计时
        set({ questionDeadline: null })
        break

      case 'answer_timeout':
        get().addMessage({
          id: nextId(), role: 'system', content: msg.message,
          messageType: 'answer_timeout', questionNum: msg.question_num, timestamp: now,
        })
        set({ questionDeadline: null })
        break

      case 'resumed': {
        // 断线重连恢复：网络抖动导致的自动重连（同一页面，store 未重置）时，消息列表里
        // 通常已经有对应题号的题目气泡了，这里只需要用后端给出的精确剩余秒数校正倒计时；
        // 而用户刷新/重开页面导致的重连（store 已清空）则需要把题目气泡也一并补回去，
        // 否则界面会一片空白、看不出面试进行到哪一步。
        set((s) => {
          const hasQuestionBubble = msg.question_num != null
            && s.messages.some((m) => m.messageType === 'question' && m.questionNum === msg.question_num)
          if (msg.question_num != null && msg.content && !hasQuestionBubble) {
            return {
              messages: [...s.messages, {
                id: nextId(), role: 'assistant', content: msg.content,
                messageType: 'question', questionNum: msg.question_num, timestamp: now,
              }],
              isInterviewing: true,
            }
          }
          return { isInterviewing: true }
        })
        if (typeof msg.remaining_seconds === 'number') {
          set({ questionDeadline: now + msg.remaining_seconds * 1000 })
        }
        get().addMessage({
          id: nextId(), role: 'system',
          content: msg.message || '已恢复连接，面试继续进行中...',
          messageType: 'text', timestamp: now,
        })
        break
      }

      case 'report':
        get().addMessage({
          id: nextId(), role: 'assistant', content: msg.content,
          messageType: 'report', timestamp: now,
        })
        break

      case 'review_plan':
        get().addMessage({
          id: nextId(), role: 'assistant', content: msg.content,
          messageType: 'review_plan', timestamp: now,
        })
        break

      case 'clarify_needed':
        // 与 'question' 分开处理：这不是面试问答，是某 stage（如 jd_analysis）语义自评信息不足，
        // 流程暂停在原地等待用户补充。isInterviewing 此时已由 jd_analysis 的 stage_change 置为
        // true，用户在同一个输入框里回复即可（走 'answer' 消息回传，后端 handleAnswer 无需区分）。
        get().addMessage({
          id: nextId(), role: 'assistant', content: msg.message,
          messageType: 'clarify_needed', stage: msg.stage, timestamp: now,
        })
        break

      case 'interview_complete':
        set({ isInterviewing: false, currentStage: '', questionDeadline: null })
        break

      case 'upload_result':
        set((s) => ({
          upload: { ...s.upload, status: 'done', stage: '', result: msg.content, detail: msg.message },
        }))
        get().addMessage({
          id: nextId(), role: 'system', content: msg.content,
          messageType: 'upload_result', timestamp: now,
          feedback: msg.message, // 校验失败详情
        })
        break

      case 'rag_evaluation':
        get().addMessage({
          id: nextId(), role: 'system', content: msg.rag_evaluation.summary,
          messageType: 'rag_evaluation', timestamp: now,
          ragEvaluation: msg.rag_evaluation,
        })
        break

      case 'error':
        // 上传过程中的失败也同步到 upload 状态（如 PDF 是扫描件、解析失败等）
        if (get().upload.status === 'uploading') {
          set((s) => ({
            upload: { ...s.upload, status: 'error', stage: '', result: msg.message, detail: null },
          }))
        }
        get().addMessage({
          id: nextId(), role: 'system', content: msg.message,
          messageType: 'text', timestamp: now,
        })
        if (get().isInterviewing) {
          set({ isInterviewing: false, currentStage: '', questionDeadline: null })
        }
        // 自增序号：面试准备面板据此判断"这次开始面试的请求失败了"，结束提交中状态、
        // 保留用户已填内容，让用户可以直接修改后重试，而不需要重新填写表单。
        set((s) => ({ lastErrorSeq: s.lastErrorSeq + 1 }))
        break
    }
  },
}))
