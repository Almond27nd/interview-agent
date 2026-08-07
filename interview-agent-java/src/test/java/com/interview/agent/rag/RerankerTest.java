/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reranker 分派器测试：验证按 app.rag.reranker.type 选对策略、未知类型回退 llm。
 */
class RerankerTest {

    private RerankStrategy fake(String type) {
        return new RerankStrategy() {
            @Override public String type() { return type; }
            @Override public List<RagDocument> rerank(String q, List<RagDocument> docs, int topN) { return docs; }
        };
    }

    @Test
    @DisplayName("按配置选中对应策略")
    void selectsConfiguredStrategy() {
        Reranker r = new Reranker(List.of(fake("llm"), fake("cross-encoder"), fake("none")), "cross-encoder");
        assertEquals("cross-encoder", r.activeType());
    }

    @Test
    @DisplayName("类型大小写不敏感")
    void caseInsensitive() {
        Reranker r = new Reranker(List.of(fake("llm"), fake("cross-encoder")), "Cross-Encoder");
        assertEquals("cross-encoder", r.activeType());
    }

    @Test
    @DisplayName("未知类型回退到 llm")
    void fallsBackToLlmOnUnknownType() {
        Reranker r = new Reranker(List.of(fake("llm"), fake("none")), "bogus");
        assertEquals("llm", r.activeType());
    }
}
