/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'

/**
 * 报告内容是后端 LLM 生成完整 JSON 解析后一次性格式化的 Markdown（结构化数据决定了
 * 无法像"提问"那样做真正的逐 token 流式生成），因此这里在前端对已收到的完整文本做
 * 逐字符"打字机"效果回放，视觉效果与真实流式一致。
 */
export function ReportCard({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false)
  const [displayed, setDisplayed] = useState('')
  const playedRef = useRef(false)

  useEffect(() => {
    if (playedRef.current) return
    playedRef.current = true
    let i = 0
    // 控制总播放时长大致恒定（约 1.2s），内容越长每步吐出的字符越多
    const step = Math.max(1, Math.ceil(content.length / 80))
    const timer = setInterval(() => {
      i += step
      if (i >= content.length) {
        setDisplayed(content)
        clearInterval(timer)
      } else {
        setDisplayed(content.slice(0, i))
      }
    }, 20)
    return () => clearInterval(timer)
  }, [content])

  const isTyping = displayed.length < content.length

  return (
    <div className="mx-4 my-3 border border-blue-200 bg-blue-50 rounded-xl overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-blue-100 transition"
      >
        <span className="font-medium text-blue-800">面试评估报告</span>
        <span className="text-blue-600 text-sm">{expanded ? '收起' : '展开'}</span>
      </button>
      {expanded && (
        <div className="px-4 pb-4 prose prose-sm max-w-none">
          <ReactMarkdown>{displayed}</ReactMarkdown>
          {isTyping && (
            <span className="inline-block w-1.5 h-4 bg-blue-500 align-middle animate-pulse ml-0.5" />
          )}
        </div>
      )}
    </div>
  )
}
