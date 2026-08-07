/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RRF（Reciprocal Rank Fusion）融合算法（与 Go 版本一致）
 * k = 60
 */
@Component
public class RRFusion {

    /** RRF 融合算法的常数 k，默认 60 */
    private static final int RRF_CONSTANT = 60;

    /**
     * 多路召回 RRF 融合
     * @param allResults 每一路的检索结果
     * @param topK 最终返回的文档数
     */
    public List<RagDocument> fuse(List<List<RagDocument>> allResults, int topK) {
        if (topK <= 0) topK = 10;

        // docID -> 融合分数
        Map<String, Double> scoreMap = new HashMap<>();
        // docID -> 文档（保留最后看到的版本）
        Map<String, RagDocument> docMap = new HashMap<>();

        for (List<RagDocument> results : allResults) {
            if (results == null) continue;
            for (int rank = 0; rank < results.size(); rank++) {
                RagDocument doc = results.get(rank);
                String id = docID(doc);
                // RRF score = sum(1 / (k + rank + 1))
                scoreMap.merge(id, 1.0 / (RRF_CONSTANT + rank + 1), Double::sum);
                docMap.put(id, doc);
            }
        }

        // 按 RRF 分数降序排列
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scoreMap.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int limit = Math.min(topK, ranked.size());
        List<RagDocument> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Double> entry = ranked.get(i);
            RagDocument doc = docMap.get(entry.getKey());
            RagDocument copy = RagDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .metadata(doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>())
                    .userId(doc.getUserId())
                    .sourceFile(doc.getSourceFile())
                    .build();
            copy.getMetadata().put("_rrf_score", entry.getValue());
            results.add(copy);
        }

        return results;
    }

    private String docID(RagDocument doc) {
        if (doc.getId() != null && !doc.getId().isEmpty()) {
            return doc.getId();
        }
        String content = doc.getContent();
        if (content != null && content.length() > 100) {
            return content.substring(0, 100);
        }
        return content != null ? content : "";
    }
}
