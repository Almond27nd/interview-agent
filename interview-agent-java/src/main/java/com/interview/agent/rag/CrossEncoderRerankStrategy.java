/**
 */
package com.interview.agent.rag;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Cross-Encoder 重排策略：用 DashScope 的 gte-rerank 专用重排模型（cross-encoder）。
 *
 * 相比 LLM 重排：把 query 与每个候选文档成对喂给专门训练的重排模型，直接输出相关性分数，
 * 速度更快、成本更低、输出更稳定（无需解析 JSON）。适合候选量大或对延迟/成本敏感的场景。
 *
 * 与 LLM 版保持一致的工程约定：不缩小召回集合——模型漏返的文档按原顺序补在末尾，只改善排序。
 * RerankModel 由 Spring AI Alibaba 的 DashScopeRerankAutoConfiguration 自动装配；
 * 若不可用（未配置 DashScope）则降级为原始顺序，不影响其他策略与应用启动。
 */
@Slf4j
@Component
public class CrossEncoderRerankStrategy implements RerankStrategy {

    private final RerankModel rerankModel;
    /** rerank 模型名。DashScope 当前可用 gte-rerank-v2（gte-rerank 老模型部分账号无权限会 403） */
    private final String model;

    public CrossEncoderRerankStrategy(ObjectProvider<RerankModel> rerankModelProvider,
                                      @Value("${app.rag.reranker.cross-encoder-model:gte-rerank-v2}") String model) {
        this.rerankModel = rerankModelProvider.getIfAvailable();
        this.model = model;
    }

    @Override
    public String type() {
        return "cross-encoder";
    }

    @Override
    public List<RagDocument> rerank(String query, List<RagDocument> docs, int topN) {
        if (docs == null || docs.size() <= 1) {
            return docs;
        }
        if (rerankModel == null) {
            log.warn("[Reranker:cross-encoder] DashScope RerankModel 不可用（未配置 DashScope），返回原始顺序");
            return docs.subList(0, Math.min(topN, docs.size()));
        }
        try {
            // RagDocument -> Spring AI Document，id 用于把重排结果映射回原文档
            Map<String, RagDocument> docMap = new HashMap<>();
            List<Document> input = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                RagDocument doc = docs.get(i);
                String id = (doc.getId() != null && !doc.getId().isEmpty()) ? doc.getId() : "doc_" + i;
                docMap.put(id, doc);
                String content = doc.getContent() != null ? doc.getContent() : "";
                input.add(Document.builder().id(id).text(content).build());
            }

            // topN 取全量，拿到完整排序后再截断，保证"不缩小召回集合"
            RerankResponse response = rerankModel.call(new RerankRequest(query, input,
                    DashScopeRerankOptions.builder().model(model).topN(docs.size()).returnDocuments(false).build()));

            List<RagDocument> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (DocumentWithScore dws : response.getResults()) {
                String id = dws.getOutput().getId();
                RagDocument doc = docMap.get(id);
                if (doc != null && seen.add(id)) {
                    result.add(doc);
                }
            }
            // 补漏：模型未返回的按原召回顺序补在末尾，不减少召回集合
            for (int i = 0; i < docs.size(); i++) {
                RagDocument doc = docs.get(i);
                String id = (doc.getId() != null && !doc.getId().isEmpty()) ? doc.getId() : "doc_" + i;
                if (seen.add(id)) {
                    result.add(doc);
                }
            }
            return result.size() > topN ? new ArrayList<>(result.subList(0, topN)) : result;
        } catch (Exception e) {
            log.warn("[Reranker:cross-encoder] 重排失败，返回原始顺序: {}", e.getMessage());
            return docs.subList(0, Math.min(topN, docs.size()));
        }
    }
}
