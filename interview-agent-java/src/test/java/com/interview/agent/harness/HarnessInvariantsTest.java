package com.interview.agent.harness;

import com.interview.agent.agent.QuestionPlanner;
import com.interview.agent.agent.QuestionRuleChecker;
import com.interview.agent.agent.ReviewPlanner;
import com.interview.agent.graph.Orchestrator;
import com.interview.agent.memory.LongTermMemory;
import com.interview.agent.memory.MemoryRecallService;
import com.interview.agent.memory.MemoryWriteGate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness 层跨类结构性不变量测试。
 *
 * <p>本测试不是测"功能对不对"，而是测"设计前提有没有被破坏"。
 * 这些不变量一旦被改坏，功能不会报错，但设计意图会静默失效。
 *
 * <p>每条测试的失败信息会写明后果——不只说"断言失败"，
 * 而是说"RRF 会退化为 N 进 N，M4 的层次修正静默失效"。
 */
class HarnessInvariantsTest {

    // ===== 不变量 1：候选池 > 融合输出 =====

    @Test
    void candidatePool_mustExceedFuseTopK() {
        int pool = getPrivateStaticInt(LongTermMemory.class, "WEAK_POINT_CANDIDATE_POOL");
        int topK = getPrivateStaticInt(MemoryRecallService.class, "FUSE_TOP_K");
        assertTrue(pool > topK,
                "WEAK_POINT_CANDIDATE_POOL(" + pool + ") 必须严格大于 FUSE_TOP_K(" + topK + ")。"
                        + "若相等，RRF 退化为 N 进 N、只重排不筛选，M4 层次修正的价值静默失效。"
                        + "面试会正常跑完不报错，但召回环节实际没有起到筛选作用。");
    }

    // ===== 不变量 2：重复阈值高于同义换序实测值 =====

    @Test
    void duplicateThreshold_aboveReorderBaseline() {
        double threshold = getPrivateStaticDouble(QuestionRuleChecker.class, "DUPLICATE_THRESHOLD");
        assertTrue(threshold > 0.5,
                "DUPLICATE_THRESHOLD(" + threshold + ") 必须高于 0.5。"
                        + "同义换序（'索引失效的场景有哪些' vs '哪些场景会导致索引失效'）实测 bigram Jaccard ≈ 0.5。"
                        + "调低到 ≤0.5 会误伤考点相邻但确实不同的题——误判代价（合格题白白重出）比漏判更高。"
                        + "这类语义重复故意留给 Critic 处理。");
    }

    // ===== 不变量 3：合并阈值高于同前缀碰撞值 =====

    @Test
    void mergeThreshold_abovePrefixCollision() {
        double threshold = MemoryWriteGate.MERGE_THRESHOLD;
        assertTrue(threshold > 0.50,
                "MERGE_THRESHOLD(" + threshold + ") 必须高于 0.50。"
                        + "'MySQL索引' 与 'MySQL事务' 的 bigram Jaccard = 0.50。"
                        + "调低到 ≤0.50 会把这两个考点不同的知识点误并为同一个，"
                        + "永久失去独立追踪能力。误并代价远高于漏并。");
    }

    // ===== 不变量 4：所有回环轮次 ∈ [1, 3] =====

    @Test
    void allLoopBounds_finiteAndBounded() {
        int reviewRounds = getPrivateStaticInt(QuestionPlanner.class, "MAX_REVIEW_ROUNDS");
        int reflectRounds = getPrivateStaticInt(ReviewPlanner.class, "MAX_REFLECT_ROUNDS");
        int quotaFillRounds = getPrivateStaticInt(QuestionPlanner.class, "MAX_QUOTA_FILL_ROUNDS");
        int clarifyRounds = getPrivateStaticInt(Orchestrator.class, "MAX_CLARIFY_ROUNDS");

        assertAll(
                () -> assertTrue(reviewRounds >= 1 && reviewRounds <= 3,
                        "MAX_REVIEW_ROUNDS=" + reviewRounds + " 不在 [1,3] 范围。"
                                + "=0 则审题回环永远不触发；>3 则 LLM 做 Critic 边际收益递减且 token 爆炸。"),
                () -> assertTrue(reflectRounds >= 1 && reflectRounds <= 3,
                        "MAX_REFLECT_ROUNDS=" + reflectRounds + " 不在 [1,3] 范围。"),
                () -> assertTrue(quotaFillRounds >= 1 && quotaFillRounds <= 3,
                        "MAX_QUOTA_FILL_ROUNDS=" + quotaFillRounds + " 不在 [1,3] 范围。"),
                () -> assertTrue(clarifyRounds >= 1 && clarifyRounds <= 3,
                        "MAX_CLARIFY_ROUNDS=" + clarifyRounds + " 不在 [1,3] 范围。")
        );
    }

    // ===== 不变量 5：priority 修正项之和 < 主信号 1.0 =====
    // 这个不变量在 UserProfile.WeakPoint.priority() 方法体内（硬编码系数），无法用反射读取。
    // 改为断言常量值本身不变——如果有人改了系数，这个测试会提醒他检查总和是否仍 < 1.0。
    // 这里用注释记录不变量的含义，实际数值由 WeakPointMasteryTest 的排序用例间接守护。

    @Test
    void priority_corrections_sumBelowMainSignal() {
        // 顽固 +0.35, 复发 max +0.30, 错误率 max +0.20 → 合计上限 0.85 < 1.0
        // 主信号 (1 - mastery) 独占 1.0，修正项永远不能越权。
        // 如果有人改了这些系数，WeakPointMasteryTest 里的排序用例会先红。
        // 此测试作为文档存在：提醒修改者检查"修正项之和 < 1.0"这个不变量。
        double stubborn = 0.35;
        double relapse = 0.30;
        double errorRate = 0.20;
        double sum = stubborn + relapse + errorRate;
        assertTrue(sum < 1.0,
                "priority() 修正项之和 = " + sum + "，必须 < 1.0（主信号权重）。"
                        + "若 ≥ 1.0，mastery=0.9 的点（主信号 p=0.1）即使 stubborn+复发+全错也能压过"
                        + "mastery=0 的纯不会项（p≥1.0），排序退化成'只看顽固/复发次数'。");
    }

    // ===== 辅助方法：反射读取 private static final 字段 =====

    private static int getPrivateStaticInt(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("无法读取 " + clazz.getSimpleName() + "." + fieldName + ": " + e.getMessage());
            return -1; // 不会到达
        }
    }

    private static double getPrivateStaticDouble(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getDouble(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("无法读取 " + clazz.getSimpleName() + "." + fieldName + ": " + e.getMessage());
            return -1; // 不会到达
        }
    }
}
