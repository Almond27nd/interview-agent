/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import {
  fetchInterviewList,
  fetchInterviewDetail,
  type InterviewSummary,
  type InterviewDetail,
} from '../api/history'

function fmtDate(s: string | null | undefined) {
  if (!s) return '-'
  const d = new Date(s)
  return Number.isNaN(d.getTime()) ? s : d.toLocaleString()
}

function fmtScore(s: number | undefined | null) {
  if (s === undefined || s === null) return '-'
  return typeof s === 'number' ? s.toFixed(1) : s
}

export function HistoryModal({ onClose }: { onClose: () => void }) {
  const [list, setList] = useState<InterviewSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [detail, setDetail] = useState<InterviewDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  useEffect(() => {
    fetchInterviewList()
      .then(setList)
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false))
  }, [])

  const openDetail = async (sessionId: string) => {
    setActiveSessionId(sessionId)
    setDetailLoading(true)
    setDetail(null)
    setError('')
    try {
      const d = await fetchInterviewDetail(sessionId)
      setDetail(d)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setDetailLoading(false)
    }
  }

  return (
    <div
      className="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
      onClick={onClose}
    >
      <div
        className="relative bg-white rounded-xl w-[90vw] max-w-3xl max-h-[85vh] flex overflow-hidden shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          onClick={onClose}
          className="absolute top-3 right-3 text-gray-400 hover:text-gray-700 text-lg leading-none"
        >
          ✕
        </button>

        <div className="w-64 border-r border-gray-200 overflow-y-auto flex-shrink-0">
          <div className="p-3 border-b border-gray-200 font-medium text-gray-700">历史面试记录</div>
          {loading && <div className="p-4 text-sm text-gray-400">加载中...</div>}
          {!loading && list.length === 0 && !error && (
            <div className="p-4 text-sm text-gray-400">暂无历史面试记录</div>
          )}
          {list.map((item) => (
            <button
              key={item.session_id}
              onClick={() => openDetail(item.session_id)}
              className={`w-full text-left px-3 py-2 border-b border-gray-100 hover:bg-gray-50 transition ${
                activeSessionId === item.session_id ? 'bg-blue-50' : ''
              }`}
            >
              <div className="text-sm font-medium text-gray-800 truncate">{item.position || '未命名岗位'}</div>
              <div className="text-xs text-gray-400 mt-0.5">{fmtDate(item.date)}</div>
              <div className="text-xs text-blue-600 mt-0.5">得分：{fmtScore(item.overall_score)}</div>
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto p-5">
          {error && <div className="text-sm text-red-500 mb-3">{error}</div>}

          {!activeSessionId && !error && (
            <div className="text-sm text-gray-400 flex items-center justify-center h-full">
              点击左侧记录查看评估报告与复习计划
            </div>
          )}

          {activeSessionId && detailLoading && (
            <div className="text-sm text-gray-400">加载详情中...</div>
          )}

          {detail && !detailLoading && (
            <div>
              <div className="flex items-center justify-between mb-4 pr-6">
                <div>
                  <div className="text-lg font-semibold text-gray-800">{detail.position || '未命名岗位'}</div>
                  <div className="text-xs text-gray-400 mt-0.5">{fmtDate(detail.createdAt)}</div>
                </div>
                <div className="text-xl font-bold text-blue-600">{fmtScore(detail.overallScore)} 分</div>
              </div>

              {detail.reportMarkdown && (
                <div className="prose prose-sm max-w-none mb-5 border border-blue-100 bg-blue-50/50 rounded-lg p-4">
                  <ReactMarkdown>{detail.reportMarkdown}</ReactMarkdown>
                </div>
              )}

              {detail.reviewPlanMarkdown && (
                <div className="prose prose-sm max-w-none border border-purple-100 bg-purple-50/50 rounded-lg p-4">
                  <ReactMarkdown>{detail.reviewPlanMarkdown}</ReactMarkdown>
                </div>
              )}

              {!detail.reportMarkdown && !detail.reviewPlanMarkdown && (
                <div className="text-sm text-gray-400">该场面试暂无评估报告/复习计划（可能未完整完成面试）。</div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
