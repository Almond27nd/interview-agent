/**
 */
package com.interview.agent.memory.eval;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** 把记忆召回评估报告渲染成 Markdown（与 RAG 评估报告风格一致）。 */
public final class MemoryEvalReportRenderer {

    private MemoryEvalReportRenderer() {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String render(MemoryRecallEvaluator.Report r) {
        StringBuilder b = new StringBuilder();

        b.append("# 记忆召回离线评估报告\n\n");
        b.append(String.format("- 运行时间：%s%n", r.getRunAt().format(TS)));
        b.append(String.format("- 样本数：%d%n", r.getSampleCount()));
        b.append(String.format("- 耗时：%s%n", r.getDuration()));
        b.append(String.format("- 语义通道降级样本：%d / %d%n%n",
                r.getHybrid().getDegradedCount(), r.getSampleCount()));

        // ===== 核心：A/B 对照 =====
        b.append("## 一、A/B 对照（核心结论）\n\n");
        b.append("| 策略 | Recall@1 | Recall@3 | MRR | Precision | F1 |\n");
        b.append("|---|---|---|---|---|---|\n");
        appendMetricsRow(b, "baseline（改造前：contains + 按最新得分排序）", r.getBaseline());
        appendMetricsRow(b, "**hybrid（M3：三路混合召回 + RRF）**", r.getHybrid());

        b.append("\n### 提升幅度\n\n");
        b.append("| 指标 | baseline | hybrid | 绝对提升 | 相对提升 |\n");
        b.append("|---|---|---|---|---|\n");
        appendDeltaRow(b, "Recall@1", r.getBaseline().getRecallAt1(), r.getHybrid().getRecallAt1());
        appendDeltaRow(b, "Recall@3", r.getBaseline().getRecallAt3(), r.getHybrid().getRecallAt3());
        appendDeltaRow(b, "MRR", r.getBaseline().getMrr(), r.getHybrid().getMrr());
        appendDeltaRow(b, "Precision", r.getBaseline().getPrecision(), r.getHybrid().getPrecision());
        appendDeltaRow(b, "F1", r.getBaseline().getF1(), r.getHybrid().getF1());

        // ===== 泄漏检测：刻意紧跟在 A/B 对照之后 =====
        // 任何人看到「Recall 提升 43%」时，必须同时看到「其中多少可能来自背答案」，
        // 否则这个数字会被当成纯粹的能力提升而误导决策。
        LeakageReport.Result leak = r.getLeakage();
        if (leak != null) {
            b.append("\n## 二、⚠️ 评估集泄漏检测（读上表前必看）\n\n");
            b.append("> 词法通道的第①级是「同义词表归一后相等」，而该表是**人工维护**的。\n");
            b.append("> 这意味着：**只要把评估样本里的 JD 技能词写进表，指标就会上涨**，\n");
            b.append("> 但这不代表对真实用户变强了 —— 它只是背下了答案（train/test 泄漏）。\n\n");
            b.append(String.format("| 项 | 值 |%n|---|---|%n"));
            b.append(String.format("| 评估集 JD 技能词总数（去重） | %d |%n", leak.getTotalSkills()));
            b.append(String.format("| 已被同义词表收录 | %d |%n", leak.getLeakedSkills()));
            b.append(String.format("| **泄漏率** | **%.1f%%** |%n", leak.getLeakRate() * 100));
            b.append(String.format("| 表外样本数 / 总样本数 | %d / %d |%n",
                    r.getOutOfTableSampleCount(), r.getSampleCount()));

            if (r.getHybridOutOfTable() != null && r.getOutOfTableSampleCount() > 0) {
                b.append("\n### 表外子集指标（可外推的真实能力）\n\n");
                b.append("> 这部分样本的 JD 技能词**完全不在同义词表里**，因此指标不受人工规则影响。\n");
                b.append("> **若整体指标很高但这里很低，说明系统只是背下了评估集。**\n");
                b.append("> 真实用户的 JD 用词不会恰好都落在表内，所以这组数字才是可信的下界。\n\n");
                MemoryRecallEvaluator.StrategyMetrics oot = r.getHybridOutOfTable();
                b.append("| 范围 | 样本数 | Recall@1 | Recall@3 | MRR | Precision |\n|---|---|---|---|---|---|\n");
                b.append(String.format("| 全集 | %d | %.4f | %.4f | %.4f | %.4f |%n",
                        r.getSampleCount(), r.getHybrid().getRecallAt1(), r.getHybrid().getRecallAt3(),
                        r.getHybrid().getMrr(), r.getHybrid().getPrecision()));
                b.append(String.format("| **表外子集** | %d | %.4f | %.4f | %.4f | %.4f |%n",
                        r.getOutOfTableSampleCount(), oot.getRecallAt1(), oot.getRecallAt3(),
                        oot.getMrr(), oot.getPrecision()));
            }

            if (leak.getLeakRate() > 0.35) {
                b.append("\n> **⚠️ 泄漏率偏高。** 上方全集指标存在虚高，应以「表外子集」为准。\n");
                b.append("> 后续若要继续提升，应扩充数据集（引入表外的新技能词），\n");
                b.append("> 而不是继续往同义词表里添加评估集中出现的词。\n");
            }
        }

        // ===== 被救回的 topic =====
        b.append("\n## 三、被 hybrid 救回的薄弱点（baseline 漏召回）\n\n");
        Map<String, Integer> rescued = r.getRescuedTopicCounts();
        if (rescued == null || rescued.isEmpty()) {
            b.append("_本次评估无新增召回。_\n");
        } else {
            b.append("> 这些是标注为「本场应重点考察」、hybrid 成功召回、而 baseline 因字符串不匹配漏掉的薄弱点。\n\n");
            b.append("| Topic | 被救回次数 |\n|---|---|\n");
            rescued.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                    .forEach(e -> b.append(String.format("| %s | %d |%n", e.getKey(), e.getValue())));
        }

        // ===== 新引入的误召回（代价侧）=====
        b.append("\n## 四、hybrid 新引入的误召回（改造的代价）\n\n");
        Map<String, Integer> fp = r.getNewFalsePositiveCounts();
        if (fp == null || fp.isEmpty()) {
            b.append("_本次评估未引入新的误召回。_\n");
        } else {
            b.append("> 这些是未标注为相关、却被 hybrid 判为强相关、而 baseline 没有误判的 topic。\n");
            b.append("> 与「救回」对称呈现，避免评估只报喜不报忧。主要来源是词法通道的\n");
            b.append("> **共享英文技术词**（如三个候选都含 `mysql` / `redis`），\n");
            b.append("> 这类误召回的代价是稀释出题 Prompt 的重点，但不会漏掉真薄弱点。\n\n");
            b.append("| Topic | 误召回次数 |\n|---|---|\n");
            fp.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                    .forEach(e -> b.append(String.format("| %s | %d |%n", e.getKey(), e.getValue())));
        }

        // ===== 按岗位分组 =====
        if (r.getByPosition() != null && !r.getByPosition().isEmpty()) {
            b.append("\n## 五、按岗位分组（hybrid）\n\n");
            b.append("> 用于检查是否存在「只在某个技术栈上有效」的偏差——若某岗位显著低于均值，\n");
            b.append("> 说明同义词表或语义 query 的领域覆盖不足，而不是算法本身有问题。\n\n");
            b.append("| 岗位 | 样本数 | Recall@1 | Recall@3 | MRR |\n|---|---|---|---|---|\n");
            r.getByPosition().forEach((pos, m) -> b.append(String.format(
                    "| %s | %d | %.4f | %.4f | %.4f |%n",
                    pos, m.getSampleCount(), m.getRecallAt1(), m.getRecallAt3(), m.getMrr())));
        }

        // ===== 反自证校验 =====
        b.append("\n## 六、反自证校验（数据集是否「自己出题自己答」）\n\n");
        b.append("> 一份只包含「我知道新方案能解决的场景」的数据集必然跑出满分，毫无意义。\n");
        b.append("> 因此这里统计四类样本的分布：若「hybrid 更差」与「两者均失败」两类为 0，\n");
        b.append("> 则应当怀疑数据集有自证倾向，而不是庆祝指标漂亮。\n\n");
        b.append("> **分类依据是逐样本 F1，而非 Recall**：词法假阳性不降低 Recall（该召回的仍召回了）、\n");
        b.append("> 只降低 Precision，若按 Recall 分类会得出「没有任何样本变差」的错误结论。\n\n");
        int better = 0, equal = 0, worse = 0, bothFail = 0;
        for (MemoryRecallEvaluator.SampleComparison c : r.getComparisons()) {
            double bf = c.baselineF1();
            double hf = c.hybridF1();
            if (bf == 0 && hf == 0) {
                bothFail++;
            } else if (hf > bf) {
                better++;
            } else if (hf < bf) {
                worse++;
            } else {
                equal++;
            }
        }
        int total = r.getComparisons().size();
        b.append("| 类别 | 样本数 | 占比 | 说明 |\n|---|---|---|---|\n");
        b.append(String.format("| hybrid 更优 | %d | %.0f%% | 改造的正向收益 |%n",
                better, pct(better, total)));
        b.append(String.format("| 两者持平 | %d | %.0f%% | 验证未把原本能召回的搞丢（防退化） |%n",
                equal, pct(equal, total)));
        b.append(String.format("| **hybrid 更差** | %d | %.0f%% | 暴露词法通道共享英文词假阳性等真实缺陷 |%n",
                worse, pct(worse, total)));
        b.append(String.format("| **两者均失败** | %d | %.0f%% | 暴露无 embedding 时纯语义关系必漏的能力上限 |%n",
                bothFail, pct(bothFail, total)));

        // ===== 逐样本明细 =====
        b.append("\n## 七、逐样本明细\n\n");
        b.append("| 样本 | 岗位 | 标注相关 | baseline 召回 | hybrid 召回 | R@3 | Prec | 救回 | 新误召 |\n");
        b.append("|---|---|---|---|---|---|---|---|---|\n");
        for (MemoryRecallEvaluator.SampleComparison c : r.getComparisons()) {
            b.append(String.format("| %s | %s | %s | %s | %s | %.2f→%.2f | %.2f→%.2f | %s | %s |%n",
                    c.getSampleId(),
                    nvl(c.getPosition()),
                    join(c.getRelevantTopics()),
                    join(c.getBaselineRecalled()),
                    join(c.getHybridRecalled()),
                    c.getBaselineRecallAt3(), c.getHybridRecallAt3(),
                    c.getBaselinePrecision(), c.getHybridPrecision(),
                    c.getRescuedTopics().isEmpty() ? "—" : join(c.getRescuedTopics()),
                    c.getNewFalsePositives() == null || c.getNewFalsePositives().isEmpty()
                            ? "—" : join(c.getNewFalsePositives())));
        }

        // ===== 指标说明 =====
        b.append("\n## 八、指标说明\n\n");
        b.append("- **Recall@K** = |前 K 条召回 ∩ 标注相关| / |标注相关|，衡量「该考的有没有被排到前面」。\n");
        b.append("  这里看 @1 / @3 而非题库评估的 @10 / @20：记忆候选集只有 2~10 条，\n");
        b.append("  @5 及以上会恒等于全量 Recall 而失去区分度；且出题 Prompt 里排最前的几条\n");
        b.append("  才真正影响 LLM 的方向规划（锚定效应）。\n");
        b.append("- **MRR** = 第一个命中的排名倒数，衡量「最该考的是不是排第一」。\n");
        b.append("- **Precision** = 判为强相关的里面确实相关的比例，防止「把所有候选都判为强相关」刷高 Recall。\n");
        b.append("- **F1** = Recall@3 与 Precision 的调和平均，单一数值反映综合水平，\n");
        b.append("  避免只看 Recall 时「滥召回」被误判为进步。\n");
        b.append("- **baseline** 复刻改造前实现（双向字符串 contains + 按最新得分升序），在评估器内独立实现\n");
        b.append("  并固定不变，以保证历史报告始终可复现（不调用已被 @Deprecated 的旧方法）。\n");
        b.append("- **空黄金标准样本**（正确行为是一条都不召回）单独计分：召回为空记满分、非空记 0，\n");
        b.append("  否则标准 Recall 公式会把「正确地什么都不做」判成最差表现。\n");

        return b.toString();
    }

    private static double pct(int part, int total) {
        return total == 0 ? 0.0 : part * 100.0 / total;
    }

    private static void appendMetricsRow(StringBuilder b, String name,
                                         MemoryRecallEvaluator.StrategyMetrics m) {
        b.append(String.format("| %s | %.4f | %.4f | %.4f | %.4f | %.4f |%n",
                name, m.getRecallAt1(), m.getRecallAt3(), m.getMrr(), m.getPrecision(), m.getF1()));
    }

    private static void appendDeltaRow(StringBuilder b, String metric, double base, double hyb) {
        double abs = hyb - base;
        String rel = base == 0
                ? (hyb > 0 ? "—（基线为 0）" : "0%")
                : String.format("%+.1f%%", abs / base * 100);
        b.append(String.format("| %s | %.4f | %.4f | %+.4f | %s |%n", metric, base, hyb, abs, rel));
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "—";
        }
        return String.join("、", list);
    }

    private static String nvl(String s) {
        return s == null ? "—" : s;
    }
}
