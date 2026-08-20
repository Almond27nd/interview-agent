/**
 */
package com.interview.agent.memory;

import com.interview.agent.rag.RRFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆混合召回（M3）单元测试。
 *
 * <p>默认走<b>降级路径</b>（不注入 {@code EmbeddingModel}），因此全部用例都不依赖网络与外部服务；
 * 语义通道单独用一个可控的假 {@code EmbeddingModel} 验证，并覆盖「语义调用抛异常时能否 fail-open」。
 */
class MemoryRecallServiceTest {

    private RRFusion rrFusion;

    @BeforeEach
    void setUp() {
        rrFusion = new RRFusion();
    }

    /** 不带语义通道的召回服务（模拟未配置 EmbeddingModel 的部署）。 */
    private MemoryRecallService lexicalOnly() {
        return new MemoryRecallService(rrFusion, null);
    }

    private static UserProfile.WeakPoint wp(String topic, double score,
                                            int hit, int wrong, boolean stubborn) {
        List<UserProfile.Evidence> evidences = new ArrayList<>();
        evidences.add(UserProfile.Evidence.builder()
                .score(score).difficulty("medium")
                .askedAt(LocalDateTime.now().minusDays(2)).build());
        return UserProfile.WeakPoint.builder()
                .topic(topic).score(score).hitCount(hit).wrongCount(wrong)
                .stubborn(stubborn).lastSeen(LocalDateTime.now().minusDays(2))
                .evidences(evidences).aliases(new ArrayList<>())
                .build();
    }

    // ==================== 基础行为 ====================

    @Test
    @DisplayName("空候选安全返回，不抛异常")
    void recall_emptyCandidates() {
        MemoryRecallService svc = lexicalOnly();
        var r1 = svc.recall(null, List.of("mysql"), "Java后端");
        assertEquals(0, r1.total());
        assertEquals("empty", r1.getStrategy());

        var r2 = svc.recall(List.of(), List.of("mysql"), "Java后端");
        assertEquals(0, r2.total());
    }

    @Test
    @DisplayName("未配置 EmbeddingModel 时自动降级为「词法 + 记忆」两路，出题流程不受影响")
    void recall_degradesWithoutEmbedding() {
        MemoryRecallService svc = lexicalOnly();
        List<UserProfile.WeakPoint> candidates = List.of(
                wp("MySQL索引", 55, 3, 3, false),
                wp("Go并发", 58, 2, 2, false));

        var result = svc.recall(candidates, List.of("mysql", "redis"), "Java后端");

        assertTrue(result.isDegraded(), "无 embedding 应标记为降级");
        assertEquals("lexical+memory(RRF)", result.getStrategy());
        assertEquals(2, result.total(), "降级不应丢弃任何候选");
    }

    @Test
    @DisplayName("所有候选都必须出现在结果里——召回只做排序与分档，绝不丢数据")
    void recall_neverDropsCandidates() {
        MemoryRecallService svc = lexicalOnly();
        List<UserProfile.WeakPoint> candidates = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candidates.add(wp("Topic" + i, 50 + i, 2, 2, false));
        }
        var result = svc.recall(candidates, List.of("mysql"), "Java后端");
        // 融合 TopK 是 10，但超出的候选仍作为「供参考」附在后面
        assertEquals(15, result.total(), "融合 TopK 之外的候选不应被丢弃");
    }

    @Test
    @DisplayName("【改动1不变量】候选池 > FUSE_TOP_K 时，RRF 才真正起筛选作用：强相关必须挤进前 10")
    void recall_poolLargerThanTopK_enablesRealSelection() {
        MemoryRecallService svc = lexicalOnly();

        // 构造上游候选池的真实形态：30 条候选中只有 1 条与本次 JD 相关，
        // 且它的固有 priority 刻意做成【最低】（掌握度高、非顽固、错误率低），
        // 模拟「跨岗位面试」——候选人历史薄弱点集中在别的技术栈，
        // 与本次 JD 强相关的那条排在 priority 榜尾。
        List<UserProfile.WeakPoint> candidates = new ArrayList<>();
        for (int i = 0; i < 29; i++) {
            // 高 priority 的无关项：低分 + 顽固 + 全错
            candidates.add(wp("UnrelatedTopic" + i, 30, 5, 5, true));
        }
        candidates.add(wp("MySQL索引", 78, 10, 1, false));   // 低 priority 但词法命中

        var result = svc.recall(candidates, List.of("mysql"), "Java后端");

        // 关键断言：JD 相关性（词法通道）必须能把它从 priority 榜尾救上来。
        // 若上游仍按 priority 硬截断到 10 条，这条根本进不了候选集，relevant 会是 0。
        assertEquals(1, result.getRelevant().size(),
                "词法命中的候选必须被判为强相关，实际: "
                        + result.getRelevant().stream().map(UserProfile.WeakPoint::getTopic).toList());
        assertEquals("MySQL索引", result.getRelevant().get(0).getTopic());
        assertEquals(30, result.total(), "候选一条都不能丢（TopK 之外仍作供参考）");
    }

    @Test
    @DisplayName("topic 为空/null 的脏数据被安全跳过")
    void recall_skipsBlankTopics() {
        MemoryRecallService svc = lexicalOnly();
        List<UserProfile.WeakPoint> candidates = new ArrayList<>();
        candidates.add(wp("MySQL索引", 55, 2, 2, false));
        candidates.add(UserProfile.WeakPoint.builder().topic(null).build());
        candidates.add(UserProfile.WeakPoint.builder().topic("  ").build());
        candidates.add(null);

        var result = svc.recall(candidates, List.of("mysql"), "Java后端");
        assertEquals(1, result.total());
    }

    // ==================== 词法通道 ====================

    @Test
    @DisplayName("词法通道：双向包含命中给满分（保住专有名词的精确匹配能力）")
    void lexicalScore_bidirectionalContains() {
        MemoryRecallService svc = lexicalOnly();
        // JD 技能「mysql」被 topic「MySQL索引」包含
        assertEquals(1.0, svc.lexicalScore(wp("MySQL索引", 55, 1, 1, false), List.of("mysql")), 1e-9);
        // 反向：topic「Redis」被 JD 技能「redis持久化」包含
        assertEquals(1.0, svc.lexicalScore(wp("Redis", 55, 1, 1, false), List.of("redis持久化")), 1e-9);
    }

    @Test
    @DisplayName("【核心】词法通道：同义词表内的等价关系必须命中，即使字面零交集")
    void lexicalScore_synonymTableParticipates() {
        MemoryRecallService svc = lexicalOnly();

        // 这些映射都在 MemoryWriteGate.CANONICAL_ALIASES 里，
        // 但 topic 与 JD 技能词【字面零交集】。
        // 修复前本方法只调 normalizeRaw（纯字符归一），不查同义词表，
        // 导致 27 组人工维护的映射「只在写入时生效、对召回毫无贡献」——
        // 7 组抽样里 6 组词法得分为 0。本测试固化修复后的行为。
        assertEquals(1.0, svc.lexicalScore(
                        wp("IO模型", 55, 3, 3, false), List.of("epoll")), 1e-9,
                "epoll → IO模型 在同义词表内，应命中");
        assertEquals(1.0, svc.lexicalScore(
                        wp("分布式事务", 55, 3, 3, false), List.of("两阶段提交")), 1e-9,
                "两阶段提交 → 分布式事务 在同义词表内，应命中");
        assertEquals(1.0, svc.lexicalScore(
                        wp("Go并发", 55, 3, 3, false), List.of("goroutine")), 1e-9,
                "goroutine → Go并发 在同义词表内，应命中");
        assertEquals(1.0, svc.lexicalScore(
                        wp("JVM垃圾回收", 55, 3, 3, false), List.of("gc调优")), 1e-9,
                "gc调优 → JVM垃圾回收 在同义词表内，应命中");
        assertEquals(1.0, svc.lexicalScore(
                        wp("消息队列可靠性", 55, 3, 3, false), List.of("幂等消费")), 1e-9,
                "幂等消费 → 消息队列可靠性 在同义词表内，应命中");
    }

    @Test
    @DisplayName("词法通道：同义词归一不得引入误判——表外词与同表内不同 canonical 的词都不应命中")
    void lexicalScore_synonymDoesNotOverMatch() {
        MemoryRecallService svc = lexicalOnly();

        // ① 同在表内但映射到【不同】canonical：不应命中
        //    gc → JVM垃圾回收，而 topic 是 MySQL索引
        assertEquals(0.0, svc.lexicalScore(
                        wp("MySQL索引", 55, 3, 3, false), List.of("gc")), 1e-9,
                "gc 映射到 JVM垃圾回收，与 MySQL索引 是不同 canonical，不应命中");

        // ② 表外词且字面零交集：不应命中（留给语义通道）
        //    注：这里必须挑一个确实不在 CANONICAL_ALIASES 里的词。
        //    「日志采集」「saga模式」等曾用于此断言，后因评估暴露运维/分布式领域覆盖不足
        //    而被收录进表，断言前提随之失效——这也说明表一旦扩充，需回头核对这类「负向断言」。
        assertEquals(0.0, svc.lexicalScore(
                        wp("领域驱动设计", 55, 3, 3, false), List.of("事件风暴")), 1e-9,
                "事件风暴 未收录进同义词表且与 topic 字面零交集，词法层应漏判并交由语义通道兜底");

        // ③ 关键：canonicalize 在召回侧刻意不做 bigram 近似合并
        //    （传空 existingTopics）。否则「MySQL索引」与「MySQL事务」
        //    这类前缀相同、考点不同的词可能被【同义词层】误并为等价而拿到满分 1.0。
        //
        //    注意断言的是「< 1.0」而不是「== 0.0」：
        //    这两个词共享 ASCII 词 mysql，会在第③级 token 重叠上拿到部分分（实测 0.267），
        //    那是另一个已知问题（共享英文技术词假阳性，见 mem_020/mem_040 样本），
        //    与本测试要守住的「同义词层不得误并」是两件事，不应混在一条断言里。
        double crossCanonical = svc.lexicalScore(
                wp("MySQL索引", 55, 3, 3, false), List.of("mysql事务隔离"));
        assertTrue(crossCanonical < 1.0,
                "mysql事务隔离 → MySQL事务，与 MySQL索引 不同 canonical，"
                        + "不应被同义词层判为等价（满分），实际: " + crossCanonical);
    }

    @Test
    @DisplayName("词法通道：完全无关时得 0（该项将被判为跨岗位参考）")
    void lexicalScore_unrelated() {
        MemoryRecallService svc = lexicalOnly();
        double score = svc.lexicalScore(wp("Flink窗口", 55, 1, 1, false), List.of("mysql", "redis"));
        assertEquals(0.0, score, 1e-9);
    }

    @Test
    @DisplayName("词法通道：别名参与匹配——归一时留档的原始写法往往更接近 JD 用词")
    void lexicalScore_aliasesParticipate() {
        MemoryRecallService svc = lexicalOnly();

        // 用一个【不在同义词表里】的别名，才能单独验证「别名参与匹配」这条路径
        // （若用表内词如 seata，会被第①级同义词层直接命中，测不出别名的独立贡献）。
        UserProfile.WeakPoint withAlias = wp("领域驱动设计", 55, 3, 3, true);
        withAlias.getAliases().add("事件风暴");

        assertEquals(0.0, svc.lexicalScore(
                        wp("领域驱动设计", 55, 3, 3, true), List.of("事件风暴")), 1e-9,
                "事件风暴 未收录进同义词表、与 topic 字面零交集，无别名时应漏判");
        assertEquals(1.0, svc.lexicalScore(withAlias, List.of("事件风暴")), 1e-9,
                "别名命中——这正是实体归一留档别名的价值");
    }

    @Test
    @DisplayName("词法通道：空 JD 技能列表安全返回 0")
    void lexicalScore_emptyJdSkills() {
        MemoryRecallService svc = lexicalOnly();
        assertEquals(0.0, svc.lexicalScore(wp("MySQL索引", 55, 1, 1, false), List.of()), 1e-9);
        assertEquals(0.0, svc.lexicalScore(wp("MySQL索引", 55, 1, 1, false), null), 1e-9);
    }

    // ==================== 相关性分档 ====================

    @Test
    @DisplayName("词法命中的进「强相关」，完全无关的进「供参考」而非被丢弃")
    void recall_partitionsRelevantAndOthers() {
        MemoryRecallService svc = lexicalOnly();
        List<UserProfile.WeakPoint> candidates = List.of(
                wp("MySQL索引", 55, 3, 3, false),   // 命中 jdSkills 的 mysql
                wp("Flink窗口", 52, 2, 2, false));  // 与 JD 无关

        var result = svc.recall(candidates, List.of("mysql", "spring"), "Java后端");

        assertEquals(1, result.getRelevant().size());
        assertEquals("MySQL索引", result.getRelevant().get(0).getTopic());
        assertEquals(1, result.getOthers().size());
        assertEquals("Flink窗口", result.getOthers().get(0).getTopic(),
                "跨岗位薄弱点应保留供参考，而不是被过滤掉");
    }

    @Test
    @DisplayName("记忆通道生效：同为词法命中时，顽固薄弱点排在偶发薄弱点之前")
    void recall_stubbornRanksHigher() {
        MemoryRecallService svc = lexicalOnly();
        UserProfile.WeakPoint occasional = wp("MySQL事务", 58, 1, 1, false);
        UserProfile.WeakPoint stubborn = wp("MySQL索引", 55, 6, 5, true);

        var result = svc.recall(List.of(occasional, stubborn),
                List.of("mysql"), "Java后端");

        assertEquals(2, result.getRelevant().size());
        assertEquals("MySQL索引", result.getRelevant().get(0).getTopic(),
                "顽固薄弱点应被 RRF 融合排到前面");
    }

    // ==================== 语义通道 + fail-open ====================

    /**
     * 可控的假 EmbeddingModel：按预设向量返回，用于在无网络环境下验证语义通道。
     * 向量刻意构造成「分布式事务」与 query 高度相似、「Flink窗口」不相似。
     */
    private static class FakeEmbeddingModel implements EmbeddingModel {
        private final Map<String, float[]> vectors;
        private final boolean shouldFail;

        FakeEmbeddingModel(Map<String, float[]> vectors, boolean shouldFail) {
            this.vectors = vectors;
            this.shouldFail = shouldFail;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            if (shouldFail) {
                throw new IllegalStateException("embedding 服务不可用（模拟）");
            }
            List<float[]> result = new ArrayList<>();
            for (String t : texts) {
                result.add(vectors.getOrDefault(t, new float[]{0f, 0f, 1f}));
            }
            return result;
        }

        @Override
        public float[] embed(String text) {
            return embed(List.of(text)).get(0);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
            throw new UnsupportedOperationException("测试不使用该入口");
        }

        @Override
        public int dimensions() {
            return 3;
        }
    }

    @Test
    @DisplayName("【核心】语义通道能召回字符串完全不匹配的同义薄弱点——修掉 contains 的漏召回")
    void recall_semanticCatchesSynonyms() {
        Map<String, float[]> vectors = new HashMap<>();
        // 注意：语义通道已从「岗位+技能栈拼成一句话」改为「逐个技能词分别 embedding、
        // 与 topic 逐一算余弦后取最大值」（析取语义，与词法通道的 Math.max 一致）。
        // 原因见 MemoryRecallService.semanticScores 的注释：拼句 query 会让通用职称
        // 成为主要语义分量，实测导致无关项相似度反而更高。
        // 因此这里为「每个技能词」而非「拼接后的句子」配置向量。
        vectors.put("seata", new float[]{1f, 0f, 0f});
        vectors.put("两阶段提交", new float[]{0.98f, 0.05f, 0f});
        // 「分布式事务」与技能词语义高度相似（字符串上却互不包含）
        vectors.put("分布式事务", new float[]{0.95f, 0.1f, 0f});
        // 「Flink窗口」语义无关
        vectors.put("Flink窗口", new float[]{0f, 0f, 1f});

        MemoryRecallService svc = new MemoryRecallService(rrFusion,
                new FakeEmbeddingModel(vectors, false));

        // 注意：这里刻意不给「分布式事务」配别名，纯靠语义通道命中
        var result = svc.recall(
                List.of(wp("分布式事务", 55, 3, 3, true), wp("Flink窗口", 52, 1, 1, false)),
                List.of("seata", "两阶段提交"), "Java后端");

        assertFalse(result.isDegraded(), "语义通道应正常工作");
        assertEquals("semantic+lexical+memory(RRF)", result.getStrategy());
        assertTrue(result.getRelevant().stream()
                        .anyMatch(w -> "分布式事务".equals(w.getTopic())),
                "「分布式事务」与「Seata/两阶段提交」字符串不匹配但语义相关，必须被判为强相关");
        assertTrue(result.getOthers().stream()
                        .anyMatch(w -> "Flink窗口".equals(w.getTopic())),
                "语义无关的「Flink窗口」应落到供参考档，而不是强相关");
    }

    @Test
    @DisplayName("语义通道取「与任一技能词的最大相似度」，而非与技能栈整体的相似度")
    void recall_semanticUsesMaxOverSkills() {
        Map<String, float[]> vectors = new HashMap<>();
        // 构造一个「只与第二个技能词相关」的场景：
        // 若实现是把技能栈拼成一句话，句向量会落在两者平均处从而被稀释；
        // 取最大值则能稳定命中。这正是改为析取语义要守住的性质。
        vectors.put("kubernetes", new float[]{1f, 0f, 0f});
        vectors.put("三色标记", new float[]{0f, 1f, 0f});
        vectors.put("Go内存管理", new float[]{0.05f, 0.99f, 0f});   // 只与「三色标记」相似
        vectors.put("MySQL索引", new float[]{0f, 0f, 1f});

        MemoryRecallService svc = new MemoryRecallService(rrFusion,
                new FakeEmbeddingModel(vectors, false));

        var result = svc.recall(
                List.of(wp("Go内存管理", 55, 3, 3, true), wp("MySQL索引", 52, 1, 1, false)),
                List.of("kubernetes", "三色标记"), "Go后端");

        assertFalse(result.isDegraded());
        assertTrue(result.getRelevant().stream()
                        .anyMatch(w -> "Go内存管理".equals(w.getTopic())),
                "只与技能栈中某一个词强相关也应被召回（析取语义），实际强相关: "
                        + result.getRelevant().stream().map(UserProfile.WeakPoint::getTopic).toList());
    }

    @Test
    @DisplayName("【fail-open】语义通道抛异常时自动降级，出题流程照常完成")
    void recall_failsOpenWhenEmbeddingThrows() {
        MemoryRecallService svc = new MemoryRecallService(rrFusion,
                new FakeEmbeddingModel(Map.of(), true));

        List<UserProfile.WeakPoint> candidates = List.of(
                wp("MySQL索引", 55, 3, 3, false),
                wp("Go并发", 58, 2, 2, false));

        var result = svc.recall(candidates, List.of("mysql"), "Java后端");

        assertTrue(result.isDegraded(), "embedding 异常应降级而不是抛出");
        assertEquals("lexical+memory(RRF)", result.getStrategy());
        assertEquals(2, result.total(), "降级后候选完整保留");
        assertEquals(1, result.getRelevant().size(), "词法通道仍应正常工作");
    }

    @Test
    @DisplayName("余弦相似度：同向为 1、正交为 0，异常输入安全返回 0")
    void cosine_boundaries() {
        assertEquals(1.0, MemoryRecallService.cosine(
                new float[]{1f, 0f}, new float[]{2f, 0f}), 1e-6);
        assertEquals(0.0, MemoryRecallService.cosine(
                new float[]{1f, 0f}, new float[]{0f, 1f}), 1e-6);
        assertEquals(0.0, MemoryRecallService.cosine(null, new float[]{1f}), 1e-9);
        assertEquals(0.0, MemoryRecallService.cosine(new float[]{1f}, new float[]{1f, 2f}), 1e-9);
        assertEquals(0.0, MemoryRecallService.cosine(new float[]{0f, 0f}, new float[]{1f, 1f}), 1e-9);
    }
}
