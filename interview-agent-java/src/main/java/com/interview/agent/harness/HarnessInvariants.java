package com.interview.agent.harness;

/**
 * Harness 层跨类结构性不变量。
 *
 * <p>本类不定义常量（常量仍归各自的类，保持封装），只声明【跨类的结构性不变量】——
 * 这些不变量是若干设计决策的价值前提，一旦被破坏，功能不会报错，但设计意图会静默失效。
 *
 * <p>每条不变量都在 {@code HarnessInvariantsTest} 里有对应断言。
 * 断言失败信息会写明后果，不只说"断言失败"。
 *
 * <p>设计原则：
 * <ul>
 *   <li>单个常量的值是可调的（业务参数）</li>
 *   <li><b>常量之间的关系是不可破坏的（设计前提）</b></li>
 *   <li>本类只守护后者</li>
 * </ul>
 *
 * <p>这是 harness engineering 里"约束必须机械化执行"原则的落地：
 * 写在注释里的"勿调低"靠人自觉，写在测试里的不变量靠 CI 守护。
 */
public final class HarnessInvariants {

    private HarnessInvariants() {
    }

    /**
     * 召回候选池必须严格大于融合输出。
     *
     * <p>守护的决策：M4 层次修正（把截断从"不知道 JD 的层"挪到"知道 JD 的层"）。
     *
     * <p>若被破坏（候选池 == TopK）：RRF 退化为"N 进 N"，只重排不筛选，
     * 融合环节实际只做了重排与打标签，<b>并未起到筛选作用</b>。
     * 面试会正常跑完、不报任何错，但 M4 的价值静默失效。
     *
     * <p>涉及常量（跨两个类）：
     * <ul>
     *   <li>{@code LongTermMemory.WEAK_POINT_CANDIDATE_POOL}（= 30）</li>
     *   <li>{@code MemoryRecallService.FUSE_TOP_K}（= 10）</li>
     * </ul>
     */
    public static final String CANDIDATE_POOL_GT_FUSE_TOPK =
            "WEAK_POINT_CANDIDATE_POOL > FUSE_TOP_K";

    /**
     * 规则层重复阈值必须高于"同义换序"实测值。
     *
     * <p>守护的决策：规则层零误判边界——同义换序（bigram Jaccard ≈ 0.5）故意留给 Critic。
     *
     * <p>若被破坏（阈值 ≤ 0.5）：同义换序的题被规则层拦下，但调低阈值同时会误伤
     * 考点相邻但确实不同的题。误判代价（合格题白白重出）比漏判更高。
     *
     * <p>涉及常量：{@code QuestionRuleChecker.DUPLICATE_THRESHOLD}（= 0.6）
     * 实测基准：同义换序 ≈ 0.5
     */
    public static final String DUPLICATE_THRESHOLD_ABOVE_REORDER =
            "DUPLICATE_THRESHOLD > 0.5";

    /**
     * 实体合并阈值必须高于"同前缀不同考点"实测值。
     *
     * <p>守护的决策：topic 归一不误并。
     *
     * <p>若被破坏（阈值 ≤ 0.50）：{@code MySQL索引} 与 {@code MySQL事务}（Jaccard = 0.50）
     * 被误并为同一个知识点，<b>永久失去独立追踪能力</b>。误并代价远高于漏并。
     *
     * <p>涉及常量：{@code MemoryWriteGate.MERGE_THRESHOLD}（= 0.75）
     * 实测基准：MySQL索引 vs MySQL事务 = 0.50
     */
    public static final String MERGE_THRESHOLD_ABOVE_PREFIX_COLLISION =
            "MERGE_THRESHOLD > 0.50";

    /**
     * 所有由代码控制的回环轮次上限必须有限且 ∈ [1, 3]。
     *
     * <p>守护的决策：防 token 爆炸——LLM 做 Critic 时"总能再挑出点毛病"，不截断会无限回炉。
     *
     * <p>若被破坏（= 0 或 > 3）：
     * <ul>
     *   <li>= 0：回环永远不触发，组件形同虚设</li>
     *   <li>> 3：token 爆炸风险，且 LLM 做 Critic 的边际收益递减</li>
     * </ul>
     *
     * <p>涉及常量（四处）：
     * <ul>
     *   <li>{@code QuestionPlanner.MAX_REVIEW_ROUNDS}（= 2）</li>
     *   <li>{@code ReviewPlanner.MAX_REFLECT_ROUNDS}（= 2）</li>
     *   <li>{@code QuestionPlanner.MAX_QUOTA_FILL_ROUNDS}（= 1）</li>
     *   <li>{@code Orchestrator.MAX_CLARIFY_ROUNDS}（= 2）</li>
     * </ul>
     */
    public static final String ALL_LOOP_BOUNDS_FINITE =
            "MAX_REVIEW_ROUNDS, MAX_REFLECT_ROUNDS, MAX_QUOTA_FILL_ROUNDS, MAX_CLARIFY_ROUNDS ∈ [1, 3]";

    /**
     * priority() 的修正项之和必须 < 主信号权重 1.0。
     *
     * <p>守护的决策：主信号（1 - mastery）永远占主导，修正项是修正而非竞争。
     *
     * <p>若被破坏（修正项之和 ≥ 1.0）：mastery=0.9 的点（主信号 p=0.1）即使
     * stubborn + 复发 2 次 + 全错，也能压过 mastery=0 的纯不会项（p ≥ 1.0），
     * 排序退化成"只看顽固/复发次数"。
     *
     * <p>涉及常量（在 {@code UserProfile.WeakPoint.priority()} 方法体内）：
     * <ul>
     *   <li>顽固：+0.35</li>
     *   <li>复发：min(0.3, relapseCount * 0.15)</li>
     *   <li>错误率：min(0.2, wrongCount/hitCount * 0.2)</li>
     *   <li>合计上限：0.35 + 0.3 + 0.2 = 0.85 &lt; 1.0</li>
     * </ul>
     */
    public static final String PRIORITY_CORRECTIONS_SUBORDINATE =
            "0.35 + 0.3 + 0.2 = 0.85 < 1.0";
}
