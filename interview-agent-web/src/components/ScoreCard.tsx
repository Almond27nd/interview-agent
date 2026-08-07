/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import type { ChatMessage } from '../types/message'

export function ScoreCard({ msg }: { msg: ChatMessage }) {
  const score = msg.score ?? 0
  const color = score >= 70 ? 'text-green-600' : score >= 50 ? 'text-yellow-600' : 'text-red-600'
  const bg = score >= 70 ? 'bg-green-50 border-green-200' : score >= 50 ? 'bg-yellow-50 border-yellow-200' : 'bg-red-50 border-red-200'

  return (
    <div className={`mx-4 my-3 p-4 rounded-xl border ${bg}`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm font-medium text-gray-500">评分</span>
        <span className={`text-2xl font-bold ${color}`}>{score}</span>
      </div>
      <p className="text-sm text-gray-700 mb-3">{msg.feedback}</p>
      {msg.keyPointsHit && msg.keyPointsHit.length > 0 && (
        <div className="mb-2">
          <span className="text-xs text-green-600 font-medium">命中要点：</span>
          <div className="flex flex-wrap gap-1 mt-1">
            {msg.keyPointsHit.map((p, i) => (
              <span key={i} className="text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded">{p}</span>
            ))}
          </div>
        </div>
      )}
      {msg.keyPointsMissed && msg.keyPointsMissed.length > 0 && (
        <div>
          <span className="text-xs text-red-600 font-medium">遗漏要点：</span>
          <div className="flex flex-wrap gap-1 mt-1">
            {msg.keyPointsMissed.map((p, i) => (
              <span key={i} className="text-xs bg-red-100 text-red-700 px-2 py-0.5 rounded">{p}</span>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
