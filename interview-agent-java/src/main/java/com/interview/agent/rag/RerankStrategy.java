/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import java.util.List;

/**
 * Rerank 重排策略。多路召回去重合并后，对候选文档按与 query 的相关性重排。
 * 三种实现可通过配置 app.rag.reranker.type 切换：
 *   - llm           ：用大模型（chatModel）做语义重排（默认）
 *   - cross-encoder ：用 DashScope gte-rerank 专用重排模型（cross-encoder）
 *   - none          ：不重排，直接截断（用于离线评估 A/B 对照）
 */
public interface RerankStrategy {

    /** 策略标识，与配置 app.rag.reranker.type 对应 */
    String type();

    /**
     * 对候选文档重排，返回前 topN 条。
     * 约定：不缩小召回集合——重排只改善顺序，漏掉的按原顺序补在末尾，避免人为拉低 Recall。
     */
    List<RagDocument> rerank(String query, List<RagDocument> docs, int topN);
}
