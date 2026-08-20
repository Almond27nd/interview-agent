/**
 */

import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import type { ChatMessage, ClientMessage } from '../types/message'
import { ScoreCard } from './ScoreCard'
import { ReportCard } from './ReportCard'
import { ReviewPlanCard } from './ReviewPlanCard'

/**
 * clarify_needed 的人工选择区：显式渲染"继续 / 退出"两个按钮 + 引导用户在下方输入框补充。
 * 不再靠猜测用户输入文本的语义（如识别"没有了"之类的自然语言）来判断意图——用户点击的
 * 就是明确的按钮，天然对应明确的动作，不需要额外一次 LLM 判断、也没有误判风险。
 */
function ClarifyNeededCard({ msg, onSend }: { msg: ChatMessage; onSend: (m: ClientMessage) => void }) {
  const [choice, setChoice] = useState<'continue' | 'quit' | null>(null)

  const handleContinue = () => {
    setChoice('continue')
    onSend({ type: 'clarify_continue' })
  }
  const handleQuit = () => {
    setChoice('quit')
    onSend({ type: 'quit_interview' })
  }

  return (
    <div className="my-3 mx-4 p-4 bg-amber-50 border border-amber-200 rounded-xl">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-amber-500 text-lg leading-none">✋</span>
        <span className="text-amber-700 font-medium">需要补充信息</span>
      </div>
      <p className="text-sm text-gray-800 whitespace-pre-wrap">{msg.content}</p>

      {choice === null ? (
        <>
          <div className="flex flex-wrap gap-2 mt-3">
            <button
              onClick={handleContinue}
              className="px-3 py-1.5 text-sm bg-white border border-amber-300 text-amber-700 rounded-lg hover:bg-amber-100"
            >
              以当前信息继续分析
            </button>
            <button
              onClick={handleQuit}
              className="px-3 py-1.5 text-sm bg-white border border-red-300 text-red-600 rounded-lg hover:bg-red-50"
            >
              结束本次面试
            </button>
          </div>
          <p className="text-xs text-amber-600 mt-2">或在下方输入框直接输入补充信息后发送，面试将基于补充内容重新分析。</p>
        </>
      ) : (
        <p className="text-xs text-amber-700 mt-3 font-medium">
          ✓ 已选择：{choice === 'continue' ? '以当前信息继续分析' : '结束本次面试'}
        </p>
      )}
    </div>
  )
}

export function MessageBubble({ msg, onSend }: { msg: ChatMessage; onSend: (m: ClientMessage) => void }) {
  if (msg.messageType === 'stage') {
    return (
      <div className="flex justify-center my-2">
        <span className="text-xs text-gray-400 bg-gray-100 px-3 py-1 rounded-full">
          {msg.content}
        </span>
      </div>
    )
  }

  if (msg.messageType === 'clarify_needed') {
    return <ClarifyNeededCard msg={msg} onSend={onSend} />
  }

  if (msg.messageType === 'answer_timeout') {
    return (
      <div className="flex justify-center my-2">
        <span className="text-xs text-red-600 bg-red-50 border border-red-200 px-3 py-1 rounded-full">
          ⏱ {msg.content}
        </span>
      </div>
    )
  }

  if (msg.messageType === 'score') {
    return <ScoreCard msg={msg} />
  }

  if (msg.messageType === 'report') {
    return <ReportCard content={msg.content} />
  }

  if (msg.messageType === 'review_plan') {
    return <ReviewPlanCard content={msg.content} />
  }

  if (msg.messageType === 'upload_result') {
    return (
      <div className="my-3 mx-4 p-4 bg-purple-50 border border-purple-200 rounded-xl">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-purple-600 font-medium">题库导入结果</span>
        </div>
        <p className="text-sm text-gray-800">{msg.content}</p>
        {msg.feedback && (
          <details className="mt-2">
            <summary className="text-xs text-gray-500 cursor-pointer hover:text-gray-700">校验失败详情</summary>
            <pre className="mt-1 text-xs text-red-600 whitespace-pre-wrap">{msg.feedback}</pre>
          </details>
        )}
      </div>
    )
  }

  if (msg.messageType === 'rag_evaluation' && msg.ragEvaluation) {
    const eval_ = msg.ragEvaluation
    const pct = (v: number) => `${Math.round(v * 100)}%`
    const barColor = (v: number) => v >= 0.7 ? 'bg-green-500' : v >= 0.4 ? 'bg-yellow-500' : 'bg-red-500'
    return (
      <div className="my-3 mx-4 p-4 bg-blue-50 border border-blue-200 rounded-xl">
        <div className="flex items-center gap-2 mb-3">
          <span className="text-blue-600 font-medium">题库诊断报告</span>
        </div>
        <div className="grid grid-cols-4 gap-3 mb-3">
          {[
            { label: '精确率', value: eval_.precision ?? eval_.relevance },
            { label: '召回率', value: eval_.recall ?? eval_.completeness },
            { label: '相关性', value: eval_.relevance },
            { label: '综合评分', value: eval_.overall },
          ].map(({ label, value }) => (
            <div key={label} className="text-center">
              <div className="text-xs text-gray-500 mb-1">{label}</div>
              <div className="text-lg font-bold text-gray-800">{pct(value)}</div>
              <div className="w-full bg-gray-200 rounded-full h-1.5 mt-1">
                <div className={`h-1.5 rounded-full ${barColor(value)}`} style={{ width: pct(value) }} />
              </div>
            </div>
          ))}
        </div>
        {eval_.skill_coverage && eval_.skill_coverage.length > 0 && (
          <div className="mb-3">
            <div className="text-xs text-gray-500 mb-1">各技能方向</div>
            <div className="flex flex-wrap gap-1.5">
              {eval_.skill_coverage.map((sc) => (
                <span
                  key={sc.skill}
                  className={`text-xs px-2 py-0.5 rounded-full ${
                    sc.quality === '充足' ? 'bg-green-100 text-green-700' :
                    sc.quality === '偏少' ? 'bg-yellow-100 text-yellow-700' :
                    'bg-red-100 text-red-700'
                  }`}
                >
                  {sc.skill}: {sc.quality}
                </span>
              ))}
            </div>
          </div>
        )}
        {eval_.summary && (
          <p className="text-sm text-gray-700">{eval_.summary}</p>
        )}
      </div>
    )
  }

  const isUser = msg.role === 'user'

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-4`}>
      <div
        className={`max-w-[75%] rounded-2xl px-4 py-3 ${
          isUser
            ? 'bg-blue-600 text-white'
            : 'bg-gray-100 text-gray-900'
        }`}
      >
        {msg.messageType === 'question' && (
          <div className="text-xs font-medium opacity-70 mb-1">
            第 {msg.questionNum} 题
          </div>
        )}
        <div className="prose prose-sm max-w-none dark:prose-invert">
          <ReactMarkdown>{msg.content}</ReactMarkdown>
          {msg.streaming && (
            <span className="inline-block w-1.5 h-4 bg-current align-middle animate-pulse ml-0.5" />
          )}
        </div>
      </div>
    </div>
  )
}