/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条样本的评估结果，与 Go 版本 SampleResult 字段一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SampleResult {

    @JsonProperty("sample_id")
    private String sampleId;

    private String query;
    private String topic;
    private String difficulty;

    /** RAG 实际返回的有序 ID 列表（Rerank 后的顺序） */
    @JsonProperty("retrieved_ids")
    private List<String> retrievedIds;

    /** 标注的相关 ID */
    @JsonProperty("relevant_ids")
    private List<String> relevantIds;

    /** 命中的交集（按检索结果中出现的顺序） */
    @JsonProperty("hit_ids")
    private List<String> hitIds;

    @JsonProperty("recall_at_10")
    private double recallAt10;

    @JsonProperty("recall_at_20")
    private double recallAt20;

    private double mrr;

    /** 第一个命中的排名（1-based，0 表示未命中） */
    @JsonProperty("first_hit_rank")
    private int firstHitRank;
}
