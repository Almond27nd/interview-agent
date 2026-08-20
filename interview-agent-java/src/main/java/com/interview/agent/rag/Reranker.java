/**
 */
package com.interview.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重排器统一入口。内部按配置 app.rag.reranker.type 选择具体策略，调用方（出题主流程 / 离线评估）无感：
 *   - cross-encoder ：DashScope gte-rerank 专用重排模型（默认，主流程出题用，见 {@link CrossEncoderRerankStrategy}）
 *   - llm           ：大模型语义重排（见 {@link LlmRerankStrategy}）
 *   - none          ：不重排（见 {@link NoneRerankStrategy}，用于评估 A/B 对照）
 */
@Slf4j
@Component
public class Reranker {

    private final RerankStrategy strategy;
    private final int topK = 10;

    public Reranker(List<RerankStrategy> strategies,
                    @Value("${app.rag.reranker.type:cross-encoder}") String type) {
        this.strategy = strategies.stream()
                .filter(s -> s.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseGet(() -> {
                    RerankStrategy fallback = strategies.stream()
                            .filter(s -> "llm".equals(s.type()))
                            .findFirst()
                            .orElse(strategies.get(0));
                    log.warn("[Reranker] 未知 rerank 类型 '{}'，回退到 '{}'", type, fallback.type());
                    return fallback;
                });
        log.info("[Reranker] 启用 rerank 策略: {}", strategy.type());
    }

    /** 默认返回前 topK 条（用于面试出题取 top1） */
    public List<RagDocument> rerank(String query, List<RagDocument> docs) {
        return rerank(query, docs, topK);
    }

    /** 返回前 topN 条（评估 / 检索按需指定 topN） */
    public List<RagDocument> rerank(String query, List<RagDocument> docs, int topN) {
        return strategy.rerank(query, docs, topN);
    }

    /** 当前生效的 rerank 类型，供离线评估报告记录与 A/B 对比 */
    public String activeType() {
        return strategy.type();
    }
}
