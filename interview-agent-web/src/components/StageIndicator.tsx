/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useChatStore } from '../store/chatStore'

const stages = [
  { key: 'jd_analysis', label: 'JD 分析' },
  { key: 'resume_match', label: '简历匹配' },
  { key: 'rag_retrieval', label: '题库检索' },
  { key: 'question_plan', label: '出题规划' },
  { key: 'interview', label: '模拟面试' },
  { key: 'evaluation', label: '评估报告' },
  { key: 'review_plan', label: '复习计划' },
]

export function StageIndicator() {
  const { currentStage, isInterviewing } = useChatStore()

  if (!isInterviewing && !currentStage) return null

  const currentIndex = stages.findIndex((s) => currentStage.startsWith(s.key))

  return (
    <div className="flex items-center gap-1 px-4 py-2 bg-white border-b overflow-x-auto">
      {stages.map((s, i) => {
        const done = i < currentIndex || currentStage === 'completed'
        const active = i === currentIndex
        return (
          <div key={s.key} className="flex items-center">
            <div
              className={`text-xs px-2 py-1 rounded-full whitespace-nowrap ${
                done
                  ? 'bg-green-100 text-green-700'
                  : active
                    ? 'bg-blue-100 text-blue-700 font-medium'
                    : 'bg-gray-100 text-gray-400'
              }`}
            >
              {s.label}
            </div>
            {i < stages.length - 1 && (
              <div className={`w-4 h-px mx-0.5 ${done ? 'bg-green-300' : 'bg-gray-200'}`} />
            )}
          </div>
        )
      })}
    </div>
  )
}
