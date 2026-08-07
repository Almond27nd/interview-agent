/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CrossEncoder（DashScope gte-rerank）重排策略单元测试。
 * 用 Mockito mock 掉真实 RerankModel（不依赖 API Key），验证「按模型返回顺序映射回原文档 +
 * 不缩小召回集合 + 异常/不可用时降级」这条确定性逻辑。
 */
class CrossEncoderRerankStrategyTest {

    private RagDocument doc(String id, String content) {
        return RagDocument.builder().id(id).content(content).build();
    }

    private DocumentWithScore scored(String id, double score) {
        DocumentWithScore dws = new DocumentWithScore();
        dws.setDocument(Document.builder().id(id).text("t").build());
        dws.setScore(score);
        return dws;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RerankModel> provider(RerankModel model) {
        ObjectProvider<RerankModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    private CrossEncoderRerankStrategy strategy(RerankModel model) {
        return new CrossEncoderRerankStrategy(provider(model), "gte-rerank-v2");
    }

    @Test
    @DisplayName("type 为 cross-encoder")
    void type_isCrossEncoder() {
        assertEquals("cross-encoder", strategy(null).type());
    }

    @Test
    @DisplayName("按模型返回的相关性顺序把候选映射回原文档")
    void rerank_reordersByModelOutput() {
        RerankModel model = mock(RerankModel.class);
        // 输入 a,b,c；模型重排为 c,a,b
        when(model.call(any(RerankRequest.class))).thenReturn(new RerankResponse(List.of(
                scored("c", 0.9), scored("a", 0.5), scored("b", 0.1))));
        CrossEncoderRerankStrategy s = strategy(model);

        List<RagDocument> out = s.rerank("q",
                List.of(doc("a", "aaa"), doc("b", "bbb"), doc("c", "ccc")), 10);

        assertEquals(List.of("c", "a", "b"), out.stream().map(RagDocument::getId).toList());
    }

    @Test
    @DisplayName("模型漏返的文档按原顺序补在末尾，不缩小召回集合")
    void rerank_appendsMissingDocs() {
        RerankModel model = mock(RerankModel.class);
        // 只返回 c,a，漏了 b
        when(model.call(any(RerankRequest.class))).thenReturn(new RerankResponse(List.of(
                scored("c", 0.9), scored("a", 0.5))));
        CrossEncoderRerankStrategy s = strategy(model);

        List<RagDocument> out = s.rerank("q",
                List.of(doc("a", "aaa"), doc("b", "bbb"), doc("c", "ccc")), 10);

        assertEquals(3, out.size(), "不应丢文档");
        assertEquals(List.of("c", "a", "b"), out.stream().map(RagDocument::getId).toList());
    }

    @Test
    @DisplayName("结果按 topN 截断")
    void rerank_truncatesToTopN() {
        RerankModel model = mock(RerankModel.class);
        when(model.call(any(RerankRequest.class))).thenReturn(new RerankResponse(List.of(
                scored("c", 0.9), scored("a", 0.5), scored("b", 0.1))));
        CrossEncoderRerankStrategy s = strategy(model);

        List<RagDocument> out = s.rerank("q",
                List.of(doc("a", "aaa"), doc("b", "bbb"), doc("c", "ccc")), 2);

        assertEquals(List.of("c", "a"), out.stream().map(RagDocument::getId).toList());
    }

    @Test
    @DisplayName("RerankModel 不可用时降级为原始顺序，不抛异常")
    void rerank_degradesWhenModelNull() {
        CrossEncoderRerankStrategy s = strategy(null);
        List<RagDocument> out = assertDoesNotThrow(() ->
                s.rerank("q", List.of(doc("a", "aaa"), doc("b", "bbb")), 10));
        assertEquals(List.of("a", "b"), out.stream().map(RagDocument::getId).toList());
    }

    @Test
    @DisplayName("模型调用异常时降级为原始顺序")
    void rerank_degradesOnException() {
        RerankModel model = mock(RerankModel.class);
        when(model.call(any(RerankRequest.class))).thenThrow(new RuntimeException("api error"));
        CrossEncoderRerankStrategy s = strategy(model);

        List<RagDocument> out = assertDoesNotThrow(() ->
                s.rerank("q", List.of(doc("a", "aaa"), doc("b", "bbb")), 10));
        assertEquals(2, out.size());
    }
}
