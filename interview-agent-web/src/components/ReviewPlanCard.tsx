/**
 */

import { useState } from 'react'
import ReactMarkdown from 'react-markdown'

export function ReviewPlanCard({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="mx-4 my-3 border border-purple-200 bg-purple-50 rounded-xl overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-purple-100 transition"
      >
        <span className="font-medium text-purple-800">复习计划</span>
        <span className="text-purple-600 text-sm">{expanded ? '收起' : '展开'}</span>
      </button>
      {expanded && (
        <div className="px-4 pb-4 prose prose-sm max-w-none">
          <ReactMarkdown>{content}</ReactMarkdown>
        </div>
      )}
    </div>
  )
}
