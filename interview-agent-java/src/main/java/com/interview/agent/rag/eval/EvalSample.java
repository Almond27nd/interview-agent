/**
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
 * 一条评估样本（评估数据集中的一行），与 Go 版本 EvalSample 字段一致。
 *
 * 用法：人工标注（或 gen-dataset 自动生成）后写入 data/eval/dataset_v1.json，
 * 由 {@link RetrievalEvaluator} 读取使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalSample {

    /** 样本唯一 ID，如 "eval_001" */
    private String id;

    /** 检索查询（模拟 Phase 1 的 SearchQuery） */
    private String query;

    /** 人工标注的相关题目 ID 列表（黄金标准） */
    @JsonProperty("relevant_doc_ids")
    private List<String> relevantDocIds;

    /** 技术领域，如 "Go 并发" / "MySQL" */
    private String topic;

    /** 难度：easy / medium / hard */
    private String difficulty;

    /** 标注说明（可选） */
    private String note;
}
