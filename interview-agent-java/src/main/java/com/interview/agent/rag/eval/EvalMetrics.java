/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag.eval;

import java.util.*;

/**
 * 检索评估指标计算工具（与 Go 版本 evaluation_metrics.go 的指标函数一致）。
 *
 * 指标定义：
 * - Recall@K = |Top-K ∩ Relevant| / |Relevant|
 * - MRR     = 第一个相关文档排名的倒数（未命中为 0）
 */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /** 命中结果：命中的 ID（按检索顺序）+ 第一个命中的排名（1-based，0 表示未命中）。 */
    public record HitResult(List<String> hits, int firstHitRank) {
    }

    /**
     * calcRecallAtK = |Top-K ∩ Relevant| / |Relevant|
     * relevantIds 为空时返回 0（这种样本本身有问题，应在标注阶段避免）。
     */
    public static double calcRecallAtK(List<String> retrievedIds, List<String> relevantIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty()) {
            return 0;
        }
        Set<String> relevantSet = new HashSet<>(relevantIds);
        int limit = Math.min(k, retrievedIds.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (relevantSet.contains(retrievedIds.get(i))) {
                hits++;
            }
        }
        return (double) hits / relevantIds.size();
    }

    /** calcMRR 第一个相关文档的排名倒数（rank 1-based，未命中返回 0）。 */
    public static double calcMRR(List<String> retrievedIds, List<String> relevantIds) {
        Set<String> relevantSet = new HashSet<>(relevantIds == null ? List.of() : relevantIds);
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (relevantSet.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    /** calcHits 返回命中的 ID（按检索结果顺序）和第一个命中的排名（1-based，0 表示未命中）。 */
    public static HitResult calcHits(List<String> retrievedIds, List<String> relevantIds) {
        Set<String> relevantSet = new HashSet<>(relevantIds == null ? List.of() : relevantIds);
        List<String> hits = new ArrayList<>();
        int firstHit = 0;
        for (int i = 0; i < retrievedIds.size(); i++) {
            String id = retrievedIds.get(i);
            if (relevantSet.contains(id)) {
                hits.add(id);
                if (firstHit == 0) {
                    firstHit = i + 1;
                }
            }
        }
        return new HitResult(hits, firstHit);
    }

    /**
     * aggregateReport 把单样本结果汇总成完整的 EvalReport
     * 包含：整体指标 / 按 topic 分组 / 按 difficulty 分组 / 异常样本 Top-10。
     */
    public static EvalReport aggregateReport(List<SampleResult> results, RagConfigSnapshot ragCfg) {
        EvalReport report = EvalReport.builder()
                .datasetVersion("v1")
                .sampleCount(results.size())
                .runAt(java.time.LocalDateTime.now())
                .config(ragCfg)
                .sampleResults(results)
                .topicMetrics(new TreeMap<>())
                .difficultyMetrics(new TreeMap<>())
                .build();

        if (results.isEmpty()) {
            return report;
        }

        report.setOverall(computeMetricsSummary(results));

        // 按 topic 分组
        Map<String, List<SampleResult>> topicGroups = new HashMap<>();
        for (SampleResult r : results) {
            if (r.getTopic() != null && !r.getTopic().isEmpty()) {
                topicGroups.computeIfAbsent(r.getTopic(), k -> new ArrayList<>()).add(r);
            }
        }
        topicGroups.forEach((topic, group) -> report.getTopicMetrics().put(topic, computeMetricsSummary(group)));

        // 按 difficulty 分组
        Map<String, List<SampleResult>> diffGroups = new HashMap<>();
        for (SampleResult r : results) {
            if (r.getDifficulty() != null && !r.getDifficulty().isEmpty()) {
                diffGroups.computeIfAbsent(r.getDifficulty(), k -> new ArrayList<>()).add(r);
            }
        }
        diffGroups.forEach((diff, group) -> report.getDifficultyMetrics().put(diff, computeMetricsSummary(group)));

        // 异常样本：按 Recall@10 升序，再按 MRR 升序，取前 10 条
        List<SampleResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> {
            if (a.getRecallAt10() != b.getRecallAt10()) {
                return Double.compare(a.getRecallAt10(), b.getRecallAt10());
            }
            return Double.compare(a.getMrr(), b.getMrr());
        });
        report.setWorstSamples(new ArrayList<>(sorted.subList(0, Math.min(10, sorted.size()))));

        return report;
    }

    public static MetricsSummary computeMetricsSummary(List<SampleResult> results) {
        if (results.isEmpty()) {
            return MetricsSummary.builder().build();
        }
        double sumR10 = 0, sumR20 = 0, sumMRR = 0;
        for (SampleResult r : results) {
            sumR10 += r.getRecallAt10();
            sumR20 += r.getRecallAt20();
            sumMRR += r.getMrr();
        }
        int n = results.size();
        return MetricsSummary.builder()
                .count(n)
                .recallAt10(roundFloat(sumR10 / n, 4))
                .recallAt20(roundFloat(sumR20 / n, 4))
                .mrr(roundFloat(sumMRR / n, 4))
                .build();
    }

    public static double roundFloat(double v, int decimals) {
        double shift = Math.pow(10, decimals);
        return Math.round(v * shift) / shift;
    }
}
