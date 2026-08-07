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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次评估运行的完整报告，与 Go 版本 EvalReport 字段一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalReport {

    @JsonProperty("dataset_version")
    private String datasetVersion;

    @JsonProperty("dataset_path")
    private String datasetPath;

    @JsonProperty("sample_count")
    private int sampleCount;

    @JsonProperty("run_at")
    private LocalDateTime runAt;

    private String duration;

    private RagConfigSnapshot config;

    private MetricsSummary overall;

    @JsonProperty("topic_metrics")
    private Map<String, MetricsSummary> topicMetrics;

    @JsonProperty("difficulty_metrics")
    private Map<String, MetricsSummary> difficultyMetrics;

    @JsonProperty("sample_results")
    private List<SampleResult> sampleResults;

    /** 按 Recall@10 升序的前 10 条 */
    @JsonProperty("worst_samples")
    private List<SampleResult> worstSamples;
}
