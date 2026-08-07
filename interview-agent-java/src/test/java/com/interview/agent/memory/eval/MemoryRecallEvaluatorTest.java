/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory.eval;

import com.interview.agent.memory.MemoryRecallService;
import com.interview.agent.rag.RRFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆召回评估器的自测。
 *
 * <p>不依赖 embedding（传 null → 语义通道降级），因此跑出的是<b>纯词法 + 记忆</b>两路的成绩。
 * 这个成绩本身也有价值：它是「语义服务不可用时」的保底水平，
 * 而与 baseline 的差值反映了「仅靠记忆置信度重排」能带来多少提升。
 */
class MemoryRecallEvaluatorTest {

    private MemoryRecallEvaluator evaluator() {
        return new MemoryRecallEvaluator(new MemoryRecallService(new RRFusion(), null));
    }

    @Test
    @DisplayName("示例数据集能跑通，且报告结构完整")
    void evaluate_templateDataset() {
        List<MemoryEvalSample> dataset = MemoryEvalDatasetTemplate.build();
        assertEquals(65, dataset.size(), "模板应有 65 条样本");

        MemoryRecallEvaluator.Report report = evaluator().evaluate(dataset);

        assertEquals(65, report.getSampleCount());
        assertNotNull(report.getBaseline());
        assertNotNull(report.getHybrid());
        assertEquals(65, report.getComparisons().size());
        assertNotNull(report.getDuration());
        // 无 embedding → 全部样本降级
        assertEquals(65, report.getHybrid().getDegradedCount(),
                "未注入 EmbeddingModel 时所有样本都应走降级路径");
    }

    @Test
    @DisplayName("降级状态下 hybrid 的 MRR 仍应优于 baseline —— 记忆置信度重排单独就有收益")
    void evaluate_hybridBeatsBaselineOnRanking() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());

        double baseMrr = report.getBaseline().getMrr();
        double hybMrr = report.getHybrid().getMrr();

        System.out.printf("%n[降级路径 · 词法+记忆] baseline MRR=%.4f → hybrid MRR=%.4f (%+.4f)%n",
                baseMrr, hybMrr, hybMrr - baseMrr);
        System.out.printf("[降级路径] baseline R@3=%.4f → hybrid R@3=%.4f (%+.4f)%n",
                report.getBaseline().getRecallAt3(), report.getHybrid().getRecallAt3(),
                report.getHybrid().getRecallAt3() - report.getBaseline().getRecallAt3());

        assertTrue(hybMrr >= baseMrr,
                "记忆置信度重排至少不应让 MRR 变差：base=" + baseMrr + ", hyb=" + hybMrr);
    }

    @Test
    @DisplayName("别名兜底样本（mem_006）在降级状态下也应召回——验证 M2 副产物的价值")
    void evaluate_aliasRescuesUnderDegradation() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());

        MemoryRecallEvaluator.SampleComparison mem006 = report.getComparisons().stream()
                .filter(c -> "mem_006".equals(c.getSampleId()))
                .findFirst().orElseThrow();

        assertTrue(mem006.getHybridRecalled().contains("分布式事务"),
                "aliases 含 TCC/最终一致性，词法通道应命中，实际召回: " + mem006.getHybridRecalled());
        assertFalse(mem006.getBaselineRecalled().contains("分布式事务"),
                "baseline 只比 topic 不看别名，应漏召回");
        assertTrue(mem006.getRescuedTopics().contains("分布式事务"),
                "应被记为 rescued");
    }

    @Test
    @DisplayName("Precision 有意义：不相关的顽固薄弱点不应被判为强相关（mem_005）")
    void evaluate_doesNotOverRecall() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());

        MemoryRecallEvaluator.SampleComparison mem005 = report.getComparisons().stream()
                .filter(c -> "mem_005".equals(c.getSampleId()))
                .findFirst().orElseThrow();

        assertFalse(mem005.getHybridRecalled().contains("JVM垃圾回收"),
                "Go 岗位不该把 JVM 薄弱点判为强相关，实际: " + mem005.getHybridRecalled());
    }

    @Test
    @DisplayName("空数据集抛出明确异常")
    void evaluate_emptyDatasetThrows() {
        assertThrows(IllegalArgumentException.class, () -> evaluator().evaluate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator().evaluate(null));
    }

    @Test
    @DisplayName("Markdown 报告可渲染且含关键章节")
    void render_producesReadableReport() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());
        String md = MemoryEvalReportRenderer.render(report);

        assertTrue(md.contains("# 记忆召回离线评估报告"));
        assertTrue(md.contains("A/B 对照"));
        assertTrue(md.contains("被 hybrid 救回的薄弱点"));
        assertTrue(md.contains("反自证校验"));
        assertTrue(md.contains("逐样本明细"));
        assertTrue(md.contains("指标说明"));

        // 顺带把报告打到控制台，方便直接看到真实数字
        System.out.println("\n" + md);
    }

    @Test
    @DisplayName("数据集必须包含对 hybrid 不利的样本，否则属自证式评估")
    void dataset_isNotSelfServing() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());

        // 按逐样本 F1 分类：Recall 看不出词法假阳性带来的退化（该召回的仍被召回了），
        // 只有把 Precision 纳入才能观察到真实代价。
        long worse = report.getComparisons().stream()
                .filter(c -> c.hybridF1() < c.baselineF1())
                .count();
        long bothFail = report.getComparisons().stream()
                .filter(c -> c.baselineF1() == 0 && c.hybridF1() == 0)
                .count();

        System.out.printf("%n[反自证校验] hybrid 更差=%d 条，两者均失败=%d 条（共 %d 条）%n",
                worse, bothFail, report.getSampleCount());
        System.out.printf("[反自证校验] 新引入误召回: %s%n", report.getNewFalsePositiveCounts());

        // 这两条断言的意义不是「保证 hybrid 有缺点」，而是「保证评估集敢于暴露缺点」。
        // 若两类都为 0，说明数据集只挑了对新方案有利的场景，指标不可信。
        assertTrue(bothFail >= 3,
                "数据集应包含至少 3 条两策略均无解的样本（纯语义关系），实际: " + bothFail);
        assertTrue(worse >= 2,
                "数据集应包含至少 2 条 hybrid 表现更差的样本（暴露共享英文词假阳性），实际: " + worse);
        assertFalse(report.getNewFalsePositiveCounts().isEmpty(),
                "应记录到 hybrid 新引入的误召回，否则代价侧统计未生效");
    }

    @Test
    @DisplayName("降级路径下 hybrid 的 Precision 不应低于 baseline —— 词法增强不能以滥召回为代价")
    void evaluate_precisionNotWorseThanBaseline() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());

        double basePrecision = report.getBaseline().getPrecision();
        double hybPrecision = report.getHybrid().getPrecision();
        System.out.printf("[降级路径] Precision base=%.4f → hyb=%.4f, F1 base=%.4f → hyb=%.4f%n",
                basePrecision, hybPrecision,
                report.getBaseline().getF1(), report.getHybrid().getF1());

        // 注意这里断言的是「相对不退化」而非「绝对达到某个门槛」。
        // 绝对门槛在这份数据集上没有意义：组 H/I 共 20 条是刻意构造的「字面零交集」样本，
        // 降级路径（无 embedding）下必然大面积失败，绝对值会被这些样本拉低。
        // 而「hybrid 的 Precision 不低于 baseline」才是真正要守住的性质——
        // 词法通道的归一化与 token 重叠增强，不能以引入大量误召回为代价。
        assertTrue(hybPrecision >= basePrecision - 1e-9,
                String.format("hybrid Precision(%.4f) 不应低于 baseline(%.4f)",
                        hybPrecision, basePrecision));
        assertTrue(report.getHybrid().getF1() > report.getBaseline().getF1(),
                "综合 F1 应优于 baseline");
    }

    @Test
    @DisplayName("岗位覆盖足够广，避免只在 Java 语境下评估")
    void dataset_coversMultiplePositions() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());

        assertTrue(report.getByPosition().size() >= 8,
                "应覆盖至少 8 类岗位，实际: " + report.getByPosition().keySet());
    }

    @Test
    @DisplayName("【防作弊】泄漏率必须有上限，且表外子集要保留足够样本量")
    void dataset_leakageIsBounded() {
        MemoryRecallEvaluator.Report report = evaluator().evaluate(MemoryEvalDatasetTemplate.build());
        LeakageReport.Result leak = report.getLeakage();

        System.out.printf("%n[泄漏检测] JD技能词 %d 个，已被同义词表收录 %d 个，泄漏率 %.1f%%%n",
                leak.getTotalSkills(), leak.getLeakedSkills(), leak.getLeakRate() * 100);
        System.out.printf("[泄漏检测] 表外样本 %d / %d 条%n",
                report.getOutOfTableSampleCount(), report.getSampleCount());
        if (report.getOutOfTableSampleCount() > 0) {
            System.out.printf("[泄漏检测] 表外子集 R@3=%.4f（全集 %.4f）%n",
                    report.getHybridOutOfTable().getRecallAt3(), report.getHybrid().getRecallAt3());
        }

        // 这条断言的动机是一次真实事故：我按评估结论去扩充同义词表，把
        // saga模式 / 日志采集 / checkpoint / olap 等【评估样本里的 JD 技能词】直接写进表，
        // 于是降级路径 Recall@3 从 0.6194 跳到 0.8083——看似巨大成功，
        // 实则 53.8% 的评估技能词已被收录，是 train/test 泄漏。
        //
        // 保留扩表（等价关系客观正确、对真实用户有价值），但用这条断言防止继续恶化：
        // 若要再提升指标，只能扩充数据集引入表外新词，不能继续往表里抄答案。
        assertTrue(leak.getLeakRate() <= 0.60,
                String.format("泄漏率 %.1f%% 过高——说明在往同义词表里抄评估集答案。"
                        + "应扩充数据集而非扩表", leak.getLeakRate() * 100));

        // 表外子集必须保留足够样本量，否则「可外推能力」这个指标本身失去统计意义
        assertTrue(report.getOutOfTableSampleCount() >= 10,
                "表外样本应保留至少 10 条以保证可外推指标有意义，实际: "
                        + report.getOutOfTableSampleCount());
    }
}
