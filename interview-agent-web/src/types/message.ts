/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

// 客户端发送的消息
export type ClientMessage =
  | { type: 'chat'; content: string }
  | { type: 'start_interview'; jd: string; resume: string }
  | { type: 'answer'; content: string }
  | { type: 'quit_interview' }
  // clarify_needed 追问上点击"以当前信息继续"按钮：不走自由文本，后端识别为约定好的
  // 哨兵值，直接结束澄清循环、带着现有信息继续分析（与 quit_interview 是同一类"显式
  // 按钮动作"消息，都不经过 answer 的自由文本通道）。
  | { type: 'clarify_continue' }
  | { type: 'upload_file'; filename: string; data: string }
  | { type: 'upload_questions'; filename: string; data: string }

// RAG 题库诊断结果
export interface SkillCoverage {
  skill: string
  covered: boolean
  quality: string
}

export interface RAGEvaluation {
  precision: number
  recall: number
  relevance: number
  completeness: number
  overall: number
  summary: string
  skill_coverage: SkillCoverage[]
  question_evals?: { index: number; relevant: boolean; reason: string }[]
}

// 服务端推送的消息
export type ServerMessage =
  | { type: 'chat_reply'; content: string }
  | { type: 'stage_change'; stage: string; message: string }
  | { type: 'question'; question_num: number; content: string }
  // 流式增量：出题/追问 LLM 逐 token 生成过程中的增量片段，用于打字机效果；
  // 随后会收到一条完整的 'question' 消息收尾（可能带来源标注等增量阶段没有的后缀）。
  | { type: 'question_delta'; question_num: number; content: string }
  | { type: 'score'; score: number; feedback: string; key_points_hit: string[]; key_points_missed: string[] }
  | { type: 'report'; content: string }
  | { type: 'review_plan'; content: string }
  | { type: 'upload_result'; content: string; message: string }
  | { type: 'rag_evaluation'; rag_evaluation: RAGEvaluation }
  | { type: 'error'; message: string }
  | { type: 'interview_complete' }
  // LLM 语义自评当前 stage 信息不足（如 JD 过于空洞），流程暂停在该 stage 原地，
  // 等待用户在同一个回答框里补充说明后（走 'answer' 消息回传）才继续；与"面试问答"的
  // question 类型区分开，前端据此渲染"请补充信息"而非"请回答面试题"的提示样式。
  | { type: 'clarify_needed'; stage: string; message: string }
  // 限时答题：候选人未在限定时间内作答，后端已把该题记为 0 分/未回答并继续下一题。
  | { type: 'answer_timeout'; question_num: number; message: string }
  // 断线重连恢复（同一后端实例内）：连接断开后，该用户在后端仍有一场进行中的面试
  // （运行在独立线程里，答题倒计时/interviewRunning 状态均未丢失），重连成功后后端
  // 会回放当前进度——若正等待候选人作答，则带上题号/题目内容/精确剩余秒数（remaining_seconds，
  // 由后端 answerCh 超时的真实起算时间计算，而非简单重置为完整时长）；若当前在出题/评分/
  // 生成报告等无需用户输入的阶段，则只带 stage/message 说明现状。
  | {
      type: 'resumed'
      question_num?: number
      content?: string
      remaining_seconds?: number
      stage?: string
      message?: string
    }

// UI 展示用的消息
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  messageType: 'text' | 'score' | 'report' | 'review_plan' | 'stage' | 'question' | 'file' | 'upload_result' | 'rag_evaluation' | 'clarify_needed' | 'answer_timeout'
  timestamp: number
  score?: number
  feedback?: string
  keyPointsHit?: string[]
  keyPointsMissed?: string[]
  questionNum?: number
  stage?: string
  ragEvaluation?: RAGEvaluation
  /** 该题目/追问是否仍在流式生成中（收到 question_delta 但尚未收到最终 question 消息收尾） */
  streaming?: boolean
}
