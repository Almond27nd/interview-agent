/**
 */

const API_BASE = '/api'

// 历史面试摘要（列表项），字段名与后端 UserProfile.InterviewRecord 的 @JsonProperty 保持一致
export interface InterviewSummary {
  session_id: string
  position: string
  overall_score: number
  date: string | null
}

// 历史面试详情。report/reviewPlan 为结构化对象（暂未在前端展开使用），
// reportMarkdown/reviewPlanMarkdown 与实时 WS 推送渲染格式完全一致，直接用于展示。
export interface InterviewDetail {
  sessionId: string
  position: string
  overallScore: number
  createdAt: string | null
  report: unknown
  reviewPlan: unknown
  reportMarkdown: string | null
  reviewPlanMarkdown: string | null
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export async function fetchInterviewList(): Promise<InterviewSummary[]> {
  const res = await fetch(`${API_BASE}/interviews`, { headers: authHeaders() })
  if (!res.ok) {
    throw new Error(`获取历史面试列表失败（${res.status}）`)
  }
  const data = await res.json()
  return data.list ?? []
}

export async function fetchInterviewDetail(sessionId: string): Promise<InterviewDetail> {
  const res = await fetch(`${API_BASE}/interviews/${encodeURIComponent(sessionId)}`, {
    headers: authHeaders(),
  })
  if (!res.ok) {
    throw new Error(`获取面试详情失败（${res.status}）`)
  }
  return res.json()
}
