/**
 */
package com.interview.agent.memory.eval;

import com.interview.agent.memory.MemoryRecallService;
import com.interview.agent.memory.UserProfile;
import com.interview.agent.rag.eval.EvalMetrics;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 记忆召回离线评估器。
 *
 * <p><b>为什么需要它</b>：M3 混合召回的单元测试只能证明「修掉了某个具体错判」，
 * 无法回答「整体召回质量提升了多少」。而这恰好是项目当前最大的短板——
 * 多项改造都已落地，却没有任何量化证据。本类用与题库检索评估<b>相同的指标口径</b>
 * （{@link EvalMetrics} 的 Recall@K / MRR），把这个问题变成可测量的。
 *
 * <p><b>核心设计：A/B 对照</b>。只报新方案的绝对值说明不了问题
 * （Recall@3 = 0.8 到底是好还是差？），所以同一份数据集同时跑两套策略：
 * <table border="1">
 *   <tr><th>策略</th><th>做法</th><th>对应</th></tr>
 *   <tr><td><b>baseline</b></td><td>双向字符串 contains 判定相关性 + 按最新得分升序</td><td>改造前的实现</td></tr>
 *   <tr><td><b>hybrid</b></td><td>{@link MemoryRecallService} 三路混合召回 + RRF 融合</td><td>M3 改造后</td></tr>
 * </table>
 * 两者的差值才是这次改造的真实收益。
 *
 * <p><b>指标口径说明</b>：这里的「检索结果」是召回后<b>按顺序排列的强相关 topic 列表</b>，
 * 「黄金标准」是人工标注的 {@code relevant_topics}。因为记忆候选集只有 10 条量级，
 * 所以看 Recall@3 / Recall@5（而不是题库评估的 Recall@10 / Recall@20）——
 * 出题 Prompt 里排在最前面的几条才真正影响 LLM 的方向规划。
 */
@Slf4j
@Component
public class MemoryRecallEvaluator {

    private final MemoryRecallService memoryRecallService;

    public MemoryRecallEvaluator(MemoryRecallService memoryRecallService) {
        this.memoryRecallService = memoryRecallService;
    }

    /** 单个策略在整个数据集上的汇总指标。 */
    @Data
    @Builder
    public static class StrategyMetrics {
        private String strategy;
        private int sampleCount;
        /**
         * Recall@1：只看排第一的那条命中没有。
         *
         * <p><b>为什么用 @1 而不是题库评估里的 @5</b>：记忆候选集只有 2~10 条，
         * 召回列表长度几乎总是 ≤ 5，导致 Recall@5 恒等于全量 Recall、与 Recall@3 数值完全相同，
         * 是一列没有信息量的冗余指标。而 @1 才真正区分「最该考的有没有被排到第一」——
         * 出题 Prompt 里第一条对 LLM 的方向规划影响最大（锚定效应）。
         */
        private double recallAt1;
        private double recallAt3;
        private double mrr;
        /** 强相关判定的精确率：判为强相关的里面有多少确实是标注相关的。 */
        private double precision;
        /** F1：Recall@3 与 Precision 的调和平均，用于防止单看一项被「滥召回 / 过度保守」误导。 */
        private double f1;
        /** 降级样本数（语义通道未生效），仅 hybrid 有意义。 */
        private int degradedCount;
    }

    /** 单条样本在两个策略下的对照结果。 */
    @Data
    @Builder
    public static class SampleComparison {
        private String sampleId;
        private String position;
        private List<String> relevantTopics;
        private List<String> baselineRecalled;
        private List<String> hybridRecalled;
        private double baselineRecallAt3;
        private double hybridRecallAt3;
        private double baselineMrr;
        private double hybridMrr;
        /**
         * 逐样本 Precision。
         *
         * <p><b>为什么必须记录它</b>：仅按 Recall 对照会得出「没有任何样本变差」的错误结论——
         * 词法通道的假阳性（如三个候选共享 ASCII 词 {@code mysql} 而被全部判为相关）
         * 并不降低 Recall（该召回的仍被召回了），只降低 Precision。
         * 若只看 Recall，这类真实退化会被完全掩盖。
         */
        private double baselinePrecision;
        private double hybridPrecision;
        /** 只有 hybrid 召回到、baseline 漏掉的 topic —— 这是改造的直接价值。 */
        private List<String> rescuedTopics;
        /** 只有 hybrid 误召回、baseline 没误召回的 topic —— 这是改造的直接代价。 */
        private List<String> newFalsePositives;
        private boolean degraded;

        /** 逐样本 F1（Recall@3 与 Precision 的调和平均），用于综合判定该样本是变好还是变差。 */
        public double baselineF1() {
            return f1(baselineRecallAt3, baselinePrecision);
        }

        public double hybridF1() {
            return f1(hybridRecallAt3, hybridPrecision);
        }

        private static double f1(double recall, double precision) {
            return (recall + precision) == 0 ? 0.0 : 2 * recall * precision / (recall + precision);
        }
    }

    /** 完整评估报告。 */
    @Data
    @Builder
    public static class Report {
        private LocalDateTime runAt;
        private int sampleCount;
        private StrategyMetrics baseline;
        private StrategyMetrics hybrid;
        private List<SampleComparison> comparisons;
        /** 按岗位分组的 hybrid 指标。 */
        private Map<String, StrategyMetrics> byPosition;
        /** 所有被 hybrid 救回来的 topic 及出现次数。 */
        private Map<String, Integer> rescuedTopicCounts;
        /** hybrid 新引入的误召回 topic 及出现次数（改造的代价侧，与 rescued 对称呈现）。 */
        private Map<String, Integer> newFalsePositiveCounts;
        /**
         * 评估集相对同义词表的泄漏情况。
         * <p>与指标并列展示，使「Recall 提升」永远伴随「其中多少可能来自背答案」一起被看到。
         */
        private LeakageReport.Result leakage;
        /** 仅在「表外子集」（同义词表未覆盖的样本）上的 hybrid 指标——可外推的真实能力。 */
        private StrategyMetrics hybridOutOfTable;
        /** 表外子集的样本数。 */
        private int outOfTableSampleCount;
        private String duration;
    }

    /**
     * 跑完整评估：同一数据集分别用 baseline 与 hybrid 策略召回，对比指标。
     */
    public Report evaluate(List<MemoryEvalSample> dataset) {
        if (dataset == null || dataset.isEmpty()) {
            throw new IllegalArgumentException("memory eval: dataset is empty");
        }
        long start = System.currentTimeMillis();

        List<SampleComparison> comparisons = new ArrayList<>();
        List<double[]> baselineRows = new ArrayList<>();   // [r@3, r@5, mrr, precision]
        List<double[]> hybridRows = new ArrayList<>();
        Map<String, Integer> rescuedCounts = new TreeMap<>();
        Map<String, Integer> falsePositiveCounts = new TreeMap<>();

        // 预先算出「表外子集」的样本 id，用于在主循环里分流指标
        List<MemoryEvalSample>[] parts = LeakageReport.partitionSamples(dataset);
        java.util.Set<String> outOfTableIds = parts[1].stream()
                .map(MemoryEvalSample::getId)
                .collect(Collectors.toSet());
        List<double[]> outOfTableRows = new ArrayList<>();
        Map<String, List<double[]>> byPositionRows = new TreeMap<>();
        int degradedCount = 0;

        for (int i = 0; i < dataset.size(); i++) {
            MemoryEvalSample sample = dataset.get(i);
            log.info("[MemEval] ({}/{}) sample={} position={}",
                    i + 1, dataset.size(), sample.getId(), sample.getPosition());

            List<UserProfile.WeakPoint> candidates = toWeakPoints(sample);
            List<String> gold = sample.getRelevantTopics() == null
                    ? List.of() : sample.getRelevantTopics();

            // ===== 策略 A：baseline（改造前的实现）=====
            List<String> baselineRecalled = baselineRecall(candidates, sample.getJdSkills());

            // ===== 策略 B：hybrid（M3 混合召回）=====
            MemoryRecallService.RecallResult result =
                    memoryRecallService.recall(candidates, sample.getJdSkills(), sample.getPosition());
            List<String> hybridRecalled = result.getRelevant().stream()
                    .map(UserProfile.WeakPoint::getTopic)
                    .collect(Collectors.toList());
            if (result.isDegraded()) {
                degradedCount++;
            }

            double[] baseRow = metricsRow(baselineRecalled, gold);
            double[] hybRow = metricsRow(hybridRecalled, gold);
            baselineRows.add(baseRow);
            hybridRows.add(hybRow);

            // 被 hybrid 救回来的：标注相关、hybrid 召回到了、但 baseline 漏掉了
            List<String> rescued = gold.stream()
                    .filter(t -> hybridRecalled.contains(t) && !baselineRecalled.contains(t))
                    .collect(Collectors.toList());
            rescued.forEach(t -> rescuedCounts.merge(t, 1, Integer::sum));

            // hybrid 新引入的误召回：未标注相关、hybrid 判为强相关、而 baseline 没有。
            // 这是改造的「代价」侧，必须与 rescued 一起统计，否则评估只报喜不报忧。
            List<String> newFp = hybridRecalled.stream()
                    .filter(t -> !gold.contains(t) && !baselineRecalled.contains(t))
                    .collect(Collectors.toList());
            newFp.forEach(t -> falsePositiveCounts.merge(t, 1, Integer::sum));

            if (sample.getPosition() != null) {
                byPositionRows.computeIfAbsent(sample.getPosition(), k -> new ArrayList<>()).add(hybRow);
            }

            // 表外子集（同义词表完全未覆盖其 JD 技能词）单独收集一份指标。
            // 这部分不受人工维护的同义词表影响，因此衡量的是【可外推到真实用户的能力】——
            // 真实用户的 JD 用词不会恰好都落在表里。
            if (outOfTableIds.contains(sample.getId())) {
                outOfTableRows.add(hybRow);
            }

            comparisons.add(SampleComparison.builder()
                    .sampleId(sample.getId())
                    .position(sample.getPosition())
                    .relevantTopics(gold)
                    .baselineRecalled(baselineRecalled)
                    .hybridRecalled(hybridRecalled)
                    .baselineRecallAt3(baseRow[1])
                    .hybridRecallAt3(hybRow[1])
                    .baselineMrr(baseRow[2])
                    .hybridMrr(hybRow[2])
                    .baselinePrecision(baseRow[3])
                    .hybridPrecision(hybRow[3])
                    .rescuedTopics(rescued)
                    .newFalsePositives(newFp)
                    .degraded(result.isDegraded())
                    .build());
        }

        Map<String, StrategyMetrics> byPosition = new TreeMap<>();
        byPositionRows.forEach((pos, rows) ->
                byPosition.put(pos, summarize("hybrid", rows, 0)));

        Report report = Report.builder()
                .runAt(LocalDateTime.now())
                .sampleCount(dataset.size())
                .baseline(summarize("baseline", baselineRows, 0))
                .hybrid(summarize("hybrid", hybridRows, degradedCount))
                .comparisons(comparisons)
                .byPosition(byPosition)
                .rescuedTopicCounts(rescuedCounts)
                .newFalsePositiveCounts(falsePositiveCounts)
                .leakage(LeakageReport.detect(dataset))
                .hybridOutOfTable(summarize("hybrid(表外)", outOfTableRows, 0))
                .outOfTableSampleCount(outOfTableRows.size())
                .build();
        report.setDuration(String.format("%.2fs", (System.currentTimeMillis() - start) / 1000.0));
        return report;
    }

    // ==================== baseline：改造前的实现 ====================

    /**
     * 复刻改造前的召回逻辑，作为 A/B 对照基线：
     * <ol>
     *   <li>相关性判定：双向字符串 {@code contains}（原 {@code Orchestrator.isWeakPointRelevant}）；</li>
     *   <li>排序：按最新一次得分升序（原 {@code LongTermMemory.getWeakPoints}）。</li>
     * </ol>
     * 刻意在这里重新实现而不是调用已被 {@code @Deprecated} 的旧方法——
     * 基线必须固定不变，否则以后旧方法被删掉，历史评估报告就无法复现了。
     */
    private List<String> baselineRecall(List<UserProfile.WeakPoint> candidates, List<String> jdSkills) {
        return candidates.stream()
                .filter(wp -> legacyRelevant(wp.getTopic(), jdSkills))
                .sorted(Comparator.comparingDouble(UserProfile.WeakPoint::getScore))
                .map(UserProfile.WeakPoint::getTopic)
                .collect(Collectors.toList());
    }

    /** 改造前的相关性判定：双向字符串包含。 */
    private static boolean legacyRelevant(String topic, List<String> jdSkills) {
        if (topic == null || jdSkills == null) {
            return false;
        }
        String topicLower = topic.toLowerCase(Locale.ROOT);
        for (String skill : jdSkills) {
            if (skill == null) {
                continue;
            }
            String s = skill.toLowerCase(Locale.ROOT);
            if (topicLower.contains(s) || s.contains(topicLower)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 指标计算 ====================

    /**
     * 返回 [recall@1, recall@3, mrr, precision]，复用题库评估的同一套指标函数。
     *
     * <p><b>空黄金标准的特殊处理</b>：数据集里刻意包含「所有候选都与岗位无关、正确行为是
     * 一个都不召回」的样本（如前端岗位面对一堆后端薄弱点）。
     * {@link EvalMetrics#calcRecallAtK} 对空 relevant 返回 0，这会把「正确地什么都不召回」
     * 误判成最差表现。因此这里显式处理：黄金标准为空时，
     * <b>召回为空即视为完全正确（三项指标记满分），召回非空则记 0</b>。
     * 不这么做的话，只看 Recall 会完全漏掉「滥召回」这类问题。
     */
    private static double[] metricsRow(List<String> recalled, List<String> gold) {
        if (gold.isEmpty()) {
            double perfect = recalled.isEmpty() ? 1.0 : 0.0;
            return new double[]{perfect, perfect, perfect, perfect};
        }
        double r1 = EvalMetrics.calcRecallAtK(recalled, gold, 1);
        double r3 = EvalMetrics.calcRecallAtK(recalled, gold, 3);
        double mrr = EvalMetrics.calcMRR(recalled, gold);
        double precision = recalled.isEmpty() ? 0.0
                : (double) recalled.stream().filter(gold::contains).count() / recalled.size();
        return new double[]{r1, r3, mrr, precision};
    }

    private static StrategyMetrics summarize(String name, List<double[]> rows, int degradedCount) {
        if (rows.isEmpty()) {
            return StrategyMetrics.builder().strategy(name).sampleCount(0).build();
        }
        double r1 = 0, r3 = 0, mrr = 0, p = 0;
        for (double[] row : rows) {
            r1 += row[0];
            r3 += row[1];
            mrr += row[2];
            p += row[3];
        }
        int n = rows.size();
        double avgR3 = r3 / n;
        double avgP = p / n;
        // F1 用「宏平均后的 Recall 与 Precision」计算，而非逐样本 F1 再平均：
        // 后者在候选集只有 2~4 条时抖动过大，不便于跨版本对比。
        double f1 = (avgR3 + avgP) == 0 ? 0.0 : 2 * avgR3 * avgP / (avgR3 + avgP);
        return StrategyMetrics.builder()
                .strategy(name)
                .sampleCount(n)
                .recallAt1(EvalMetrics.roundFloat(r1 / n, 4))
                .recallAt3(EvalMetrics.roundFloat(avgR3, 4))
                .mrr(EvalMetrics.roundFloat(mrr / n, 4))
                .precision(EvalMetrics.roundFloat(avgP, 4))
                .f1(EvalMetrics.roundFloat(f1, 4))
                .degradedCount(degradedCount)
                .build();
    }

    // ==================== 候选池大小敏感性分析（组 J 专项） ====================

    /**
     * 对一组样本在不同候选池大小下运行召回评估，量化 pool 值对 Recall/MRR/Precision 的影响。
     * <p>
     * 在评估侧模拟 {@code LongTermMemory.getWeakPointCandidates(poolSize)} 的截断行为——
     * 按 {@code priority()} 排序后取 top poolSize，再交给 {@link MemoryRecallService#recall} 做三路融合。
     * <b>不改动生产代码</b>的 {@code WEAK_POINT_CANDIDATE_POOL} 常量。
     *
     * @param samples   评估样本（应为组 J 的大候选池样本）
     * @param poolSizes 要扫描的 pool 值列表，如 [10, 15, 20, 25, 30, 40, 50]
     * @return 按 pool 值组织的指标结果
     */
    public List<PoolSensitivityResult> runPoolSensitivityAnalysis(
            List<MemoryEvalSample> samples, List<Integer> poolSizes) {
        List<PoolSensitivityResult> results = new ArrayList<>();
        for (int poolSize : poolSizes) {
            double totalR1 = 0, totalR3 = 0, totalMrr = 0, totalP = 0;
            int validSamples = 0;
            for (MemoryEvalSample sample : samples) {
                List<UserProfile.WeakPoint> allCandidates = toWeakPoints(sample);
                // 模拟 pool 截断：按 priority 排序取 top poolSize
                List<UserProfile.WeakPoint> pooled = allCandidates.stream()
                        .sorted(Comparator.comparingDouble(UserProfile.WeakPoint::priority).reversed())
                        .limit(poolSize)
                        .collect(Collectors.toList());
                if (pooled.isEmpty()) {
                    continue;
                }
                MemoryRecallService.RecallResult result =
                        memoryRecallService.recall(pooled, sample.getJdSkills(), sample.getPosition());
                List<String> recalled = result.getRelevant().stream()
                        .map(UserProfile.WeakPoint::getTopic)
                        .collect(Collectors.toList());
                List<String> gold = sample.getRelevantTopics() == null
                        ? List.of() : sample.getRelevantTopics();
                double[] metrics = metricsRow(recalled, gold);
                totalR1 += metrics[0];
                totalR3 += metrics[1];
                totalMrr += metrics[2];
                totalP += metrics[3];
                validSamples++;
            }
            results.add(PoolSensitivityResult.builder()
                    .poolSize(poolSize)
                    .sampleCount(validSamples)
                    .recallAt1(EvalMetrics.roundFloat(validSamples > 0 ? totalR1 / validSamples : 0, 4))
                    .recallAt3(EvalMetrics.roundFloat(validSamples > 0 ? totalR3 / validSamples : 0, 4))
                    .mrr(EvalMetrics.roundFloat(validSamples > 0 ? totalMrr / validSamples : 0, 4))
                    .precision(EvalMetrics.roundFloat(validSamples > 0 ? totalP / validSamples : 0, 4))
                    .build());
        }
        return results;
    }

    /** 单个 pool 值下的评估结果。 */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PoolSensitivityResult {
        private int poolSize;
        private int sampleCount;
        private double recallAt1;
        private double recallAt3;
        private double mrr;
        private double precision;
    }

    // ==================== 样本 → 领域对象 ====================

    /**
     * 把声明式的候选描述还原成 {@link UserProfile.WeakPoint}。
     * <p>用「相对天数」还原时间，保证数据集不随时间推移失效、评估结果可复现。
     */
    private static List<UserProfile.WeakPoint> toWeakPoints(MemoryEvalSample sample) {
        List<UserProfile.WeakPoint> list = new ArrayList<>();
        if (sample.getCandidates() == null) {
            return list;
        }
        LocalDateTime now = LocalDateTime.now();
        for (MemoryEvalSample.CandidateSpec spec : sample.getCandidates()) {
            LocalDateTime askedAt = now.minusDays(Math.max(0, spec.getDaysAgo()));
            List<UserProfile.Evidence> evidences = new ArrayList<>();
            evidences.add(UserProfile.Evidence.builder()
                    .score(spec.getScore())
                    .askedAt(askedAt)
                    .difficulty(spec.getDifficulty())
                    .sessionId("eval")
                    .build());

            list.add(UserProfile.WeakPoint.builder()
                    .topic(spec.getTopic())
                    .score(spec.getScore())
                    .hitCount(spec.getHitCount())
                    .wrongCount(spec.getWrongCount())
                    .stubborn(spec.isStubborn())
                    .relapseCount(spec.getRelapseCount())
                    .lastSeen(askedAt)
                    .firstSeen(askedAt)
                    .evidences(evidences)
                    .aliases(spec.getAliases() == null ? new ArrayList<>() : new ArrayList<>(spec.getAliases()))
                    .build());
        }
        return list;
    }
}
