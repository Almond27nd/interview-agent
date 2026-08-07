/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 不重排：保持召回原始顺序，仅截断到 topN。用于离线评估时与 llm / cross-encoder 做 A/B 对照。
 */
@Component
public class NoneRerankStrategy implements RerankStrategy {

    @Override
    public String type() {
        return "none";
    }

    @Override
    public List<RagDocument> rerank(String query, List<RagDocument> docs, int topN) {
        if (docs == null) {
            return null;
        }
        return docs.size() > topN ? new ArrayList<>(docs.subList(0, topN)) : docs;
    }
}
