/**
 */
package com.interview.agent.memory.eval;

import com.interview.agent.memory.MemoryWriteGate;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 评估集泄漏检测：衡量「答案有多少被抄进了规则表」。
 *
 * <h2>为什么需要这个东西</h2>
 * 词法通道的第①级是「同义词表归一后相等」，而同义词表是<b>人工维护</b>的。
 * 这带来一个隐蔽而严重的问题：<b>只要把评估样本里的 JD 技能词写进表里，指标就会上涨</b>，
 * 但这不代表系统对真实用户变强了——它只是背下了答案。
 *
 * <p>这不是假想的风险。实际发生过：我按「运维/大数据/测试领域指标偏低」这个评估结论去扩充同义词表，
 * 扩完后降级路径的 Recall@3 从 0.6194 跳到 0.8083，看起来是巨大成功。
 * 但同时出现两个反常信号：
 * <ol>
 *   <li>反自证统计里「两策略均失败」的样本从 <b>15 条骤降到 4 条</b>——
 *       刻意构造的困难样本集体失效了；</li>
 *   <li>语义通道的独立贡献从 +0.0806 <b>转为净负数</b>
 *       （Recall@1 −0.05、MRR −0.033、Precision −0.096）。</li>
 * </ol>
 * 追查后确认：<b>53.8% 的评估 JD 技能词已被同义词表收录</b>。
 * 我扩表时直接照抄了 {@code saga模式}、{@code 日志采集}、{@code checkpoint}、{@code olap}
 * 这些评估样本里的词——本质是 <b>train/test 泄漏</b>：用测试集的答案构造规则，
 * 再在同一测试集上宣称提升。
 *
 * <h2>为什么不把扩表回滚掉</h2>
 * 因为<b>扩表本身对真实用户是有价值的</b>：{@code docker ↔ 容器隔离原理}
 * 是客观正确的等价关系，不因为它出现在评估集里就变错。
 * 回滚等于为了指标好看而删掉正确的功能，方向反了。
 *
 * <p>正确的做法是<b>让泄漏可见、可度量、有上限</b>：
 * <ul>
 *   <li>把泄漏率作为报告的常驻章节，与指标并列展示——
 *       任何人看到 Recall 提升时，同时会看到「其中多少可能来自背答案」；</li>
 *   <li>用测试卡住泄漏率上限，防止后续再靠抄词典刷分；</li>
 *   <li>区分「表内命中」与「表外命中」分别统计指标（见 {@link #partitionSamples}），
 *       <b>表外子集上的成绩才是可外推的真实能力</b>。</li>
 * </ul>
 *
 * <h2>更根本的教训</h2>
 * 规则型组件（同义词表、正则、白名单）天生容易泄漏，因为「加一条规则」和
 * 「记住一个答案」在实现上是同一个动作。而模型型组件（embedding）不容易——
 * 你没法为了让某条样本通过而去手改模型权重。
 * <b>所以规则层的评估必须额外配一个泄漏度量，否则它的指标是不可信的。</b>
 */
public final class LeakageReport {

    private LeakageReport() {
    }

    /** 泄漏检测结果。 */
    @Data
    @Builder
    public static class Result {
        /** 评估集里出现过的 JD 技能词总数（去重后）。 */
        private int totalSkills;
        /** 其中已被同义词表收录的数量。 */
        private int leakedSkills;
        /** 泄漏率 = leakedSkills / totalSkills。 */
        private double leakRate;
        /** 已收录的词及其映射（形如 {@code "saga模式 → 分布式事务"}），便于人工复核。 */
        private Set<String> leakedMappings;
        /** 未被收录的词——评估在这部分上仍有真实区分度。 */
        private Set<String> cleanSkills;
    }

    /**
     * 检测数据集相对同义词表的泄漏程度。
     *
     * <p>判定方式：对每个 JD 技能词调用
     * {@link MemoryWriteGate#canonicalize(String, java.util.Collection)}（传空集合，
     * 使其<b>只走同义词表精确命中</b>，不触发 bigram 近似合并）。
     * 若返回值与原文不同，说明该词被表覆盖 —— 词法通道能直接命中它，无需任何语义能力。
     */
    public static Result detect(List<MemoryEvalSample> dataset) {
        Set<String> allSkills = new LinkedHashSet<>();
        for (MemoryEvalSample s : dataset) {
            if (s.getJdSkills() != null) {
                s.getJdSkills().stream()
                        .filter(k -> k != null && !k.isBlank())
                        .map(String::trim)
                        .forEach(allSkills::add);
            }
        }

        Set<String> leaked = new TreeSet<>();
        Set<String> clean = new TreeSet<>();
        for (String skill : allSkills) {
            String canon = MemoryWriteGate.canonicalize(skill, List.of());
            if (!canon.equalsIgnoreCase(skill)) {
                leaked.add(skill + " → " + canon);
            } else {
                clean.add(skill);
            }
        }

        int total = allSkills.size();
        return Result.builder()
                .totalSkills(total)
                .leakedSkills(leaked.size())
                .leakRate(total == 0 ? 0.0 : (double) leaked.size() / total)
                .leakedMappings(leaked)
                .cleanSkills(clean)
                .build();
    }

    /**
     * 把数据集切成「表内子集」与「表外子集」。
     *
     * <p><b>这是本类最有价值的输出</b>：表外子集上的指标不受同义词表影响，
     * 因此它衡量的是<b>可外推到真实用户的能力</b>——真实用户的 JD 用词不会恰好都在表里。
     * 若整体指标很高但表外子集很低，说明系统只是背下了评估集。
     *
     * @return 长度为 2 的数组：[0] = 至少一个技能词被表覆盖的样本，[1] = 完全不被覆盖的样本
     */
    @SuppressWarnings("unchecked")
    public static List<MemoryEvalSample>[] partitionSamples(List<MemoryEvalSample> dataset) {
        List<MemoryEvalSample> inTable = new java.util.ArrayList<>();
        List<MemoryEvalSample> outTable = new java.util.ArrayList<>();
        for (MemoryEvalSample s : dataset) {
            boolean anyCovered = s.getJdSkills() != null && s.getJdSkills().stream()
                    .filter(k -> k != null && !k.isBlank())
                    .anyMatch(k -> !MemoryWriteGate.canonicalize(k.trim(), List.of())
                            .equalsIgnoreCase(k.trim()));
            (anyCovered ? inTable : outTable).add(s);
        }
        return new List[]{inTable, outTable};
    }
}
