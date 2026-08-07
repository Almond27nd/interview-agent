/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 文档（统一的文档表示）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument {
    private String id;
    private String content;
    private Map<String, Object> metadata;
    private String userId;
    private String sourceFile;
    private float score;
}
