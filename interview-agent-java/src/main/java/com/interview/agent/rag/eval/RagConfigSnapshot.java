/**
 */
package com.interview.agent.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评估时的 RAG 配置快照（用于 A/B 对比时回溯当时的参数），与 Go 版本 RAGConfig 一致。
 *
 * 跑评估时把当前 RAG 流水线的关键参数记到这里，写入报告。
 * 比较两份报告时，从这个字段就能看出"两次实验的参数差异在哪"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class RagConfigSnapshot {

    @JsonProperty("embedding_model")
    private String embeddingModel;

    @JsonProperty("vector_dim")
    private int vectorDim;

    @JsonProperty("vector_top_k")
    private int vectorTopK;

    @JsonProperty("bm25_top_k")
    private int bm25TopK;

    @JsonProperty("bm25_k1")
    private double bm25K1;

    @JsonProperty("bm25_b")
    private double bm25B;

    /** "llm" / "gte-rerank" / "none" */
    @JsonProperty("reranker_type")
    private String rerankerType;

    @JsonProperty("rerank_top_n")
    private int rerankTopN;

    /** 实验备注，如 "baseline" / "调 BM25 k1 到 1.2" */
    private String note;
}
