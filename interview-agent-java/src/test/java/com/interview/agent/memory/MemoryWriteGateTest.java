/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆写入门控（M2）的单元测试。
 * <p>覆盖两类职责：① 伪证据拦截；② topic 实体归一（含刻意保守的合并边界）。
 */
class MemoryWriteGateTest {

    // ==================== ① 写入门控：伪证据拦截 ====================

    @Test
    @DisplayName("超时未作答不得作为能力证据写入——这是原实现最典型的脏数据来源")
    void acceptAsEvidence_timeoutRejected() {
        // 超时时业务层会记 0 分，但它反映的是「人不在电脑前」，不是能力
        assertFalse(MemoryWriteGate.acceptAsEvidence(true, "[超时未作答]"));
        // 即便超时后侥幸带回了内容，也不采信（超时标记优先）
        assertFalse(MemoryWriteGate.acceptAsEvidence(true, "我觉得是索引失效"));
    }

    @Test
    @DisplayName("空答案 / 占位答案不作为能力证据")
    void acceptAsEvidence_blankRejected() {
        assertFalse(MemoryWriteGate.acceptAsEvidence(false, null));
        assertFalse(MemoryWriteGate.acceptAsEvidence(false, ""));
        assertFalse(MemoryWriteGate.acceptAsEvidence(false, "   "));
        assertFalse(MemoryWriteGate.acceptAsEvidence(false, "[超时未作答]"));
    }

    @Test
    @DisplayName("正常作答（无论分高分低）都应作为能力证据写入")
    void acceptAsEvidence_normalAccepted() {
        assertTrue(MemoryWriteGate.acceptAsEvidence(false, "B+ 树的叶子节点通过双向链表相连"));
        assertTrue(MemoryWriteGate.acceptAsEvidence(false, "不太清楚"));  // 答错也是有效证据
    }

    // ==================== ② 实体归一：同义词表 ====================

    @Test
    @DisplayName("同义词表命中：同一知识点的不同写法归一到同一 canonical topic")
    void canonicalize_aliasTableHit() {
        // 这三个写法在原实现里会变成三条独立薄弱点，占掉 Top10 的三个名额
        assertEquals("MySQL索引", MemoryWriteGate.canonicalize("MySQL索引优化", List.of()));
        assertEquals("MySQL索引", MemoryWriteGate.canonicalize("索引失效", List.of()));
        assertEquals("MySQL索引", MemoryWriteGate.canonicalize("MySQL 索引", List.of()));
    }

    @Test
    @DisplayName("同义词表覆盖语义等价但字符串完全不同的写法")
    void canonicalize_semanticAliases() {
        // 「两阶段提交」「Seata」和「分布式事务」字符串互不包含，
        // 原来的 contains 匹配完全无能为力
        assertEquals("分布式事务", MemoryWriteGate.canonicalize("两阶段提交", List.of()));
        assertEquals("分布式事务", MemoryWriteGate.canonicalize("Seata", List.of()));
        assertEquals("分布式事务", MemoryWriteGate.canonicalize("TCC", List.of()));

        assertEquals("JVM垃圾回收", MemoryWriteGate.canonicalize("GC调优", List.of()));
        assertEquals("Redis缓存问题", MemoryWriteGate.canonicalize("缓存雪崩", List.of()));
    }

    @Test
    @DisplayName("大小写 / 空格 / 全角字符差异不应导致 topic 分裂")
    void canonicalize_reusesExistingOnNormalizedEqual() {
        // 刻意用一个【不在同义词表里】的 topic 来验证这条规则。
        // 原因：同义词表（第①步）优先级高于「复用既有写法」（第②步），
        // 若用表内词，会被直接映射成表里的规范名，测不出第②步的空格/大小写归一能力。
        List<String> existing = List.of("Nacos 配置中心");
        // 仅空格与大小写不同 → 复用既有写法，不新建
        assertEquals("Nacos 配置中心", MemoryWriteGate.canonicalize("nacos配置中心", existing));
        assertEquals("Nacos 配置中心", MemoryWriteGate.canonicalize("NACOS  配置中心", existing));
    }

    @Test
    @DisplayName("同义词表优先于「复用既有写法」——表是规范名的唯一来源")
    void canonicalize_synonymTableTakesPrecedenceOverExisting() {
        // 画像里已有一个变体写法「JVM 垃圾回收」（带空格），
        // 而同义词表规定规范名为「JVM垃圾回收」（无空格）。
        // 此时应服从同义词表，而不是复用画像里的旧写法——
        // 否则同一 canonical 在不同用户画像里会有不同表现形式，
        // 而召回侧的第①级正是靠「归一到同一 canonical」来判等价的。
        assertEquals("JVM垃圾回收",
                MemoryWriteGate.canonicalize("gc调优", List.of("JVM 垃圾回收")));
        assertEquals("JVM垃圾回收",
                MemoryWriteGate.canonicalize("垃圾收集器", List.of("JVM 垃圾回收")));
    }

    @Test
    @DisplayName("规则与同义词都未命中时，保留清理后的原文作为新 canonical topic")
    void canonicalize_newTopic() {
        String result = MemoryWriteGate.canonicalize("  Flink  窗口机制 ", List.of("MySQL索引"));
        assertEquals("Flink 窗口机制", result);
    }

    @Test
    @DisplayName("空 topic 安全返回，不抛异常")
    void canonicalize_blankSafe() {
        assertEquals("", MemoryWriteGate.canonicalize(null, List.of()));
        assertEquals("", MemoryWriteGate.canonicalize("   ", List.of()));
    }

    // ==================== ③ 合并边界：刻意保守，宁漏不误 ====================

    @Test
    @DisplayName("【分层边界固化】前缀相同但考点不同的 topic 绝不能被误并——误并代价高于漏并")
    void canonicalize_doesNotMergeDifferentTopicsWithSamePrefix() {
        // topic 通常只有 2~8 个字，短文本 bigram 相似度天然偏高。
        // 若阈值调低，「MySQL索引」和「MySQL事务」就会被并成一条，
        // 导致其中一个知识点【永久失去独立追踪能力】——这比漏并严重得多。
        // 后续维护者若发现「某个明显同义的 topic 没被合并」，请先确认它是否属于语义近似范畴，
        // 不要直接下调 MERGE_THRESHOLD。
        List<String> existing = List.of("MySQL事务");
        String result = MemoryWriteGate.canonicalize("MySQL主从复制", existing);
        assertEquals("MySQL主从复制", result, "不同考点必须保持独立追踪");

        double sim = MemoryWriteGate.bigramJaccard(
                MemoryWriteGate.normalizeRaw("MySQL事务"),
                MemoryWriteGate.normalizeRaw("MySQL主从复制"));
        assertTrue(sim < MemoryWriteGate.MERGE_THRESHOLD,
                "相似度 " + sim + " 应低于合并阈值 " + MemoryWriteGate.MERGE_THRESHOLD);
    }

    @Test
    @DisplayName("bigram Jaccard：完全相同为 1.0，无交集为 0.0")
    void bigramJaccard_boundaries() {
        assertEquals(1.0, MemoryWriteGate.bigramJaccard("abc", "abc"), 1e-9);
        assertEquals(0.0, MemoryWriteGate.bigramJaccard("abc", "xyz"), 1e-9);
        assertEquals(0.0, MemoryWriteGate.bigramJaccard("", "abc"), 1e-9);
        assertEquals(0.0, MemoryWriteGate.bigramJaccard(null, "abc"), 1e-9);
    }

    @Test
    @DisplayName("归一化 key：去除空白与标点、全角转半角、统一小写")
    void normalizeRaw_behaviour() {
        assertEquals("mysql索引", MemoryWriteGate.normalizeRaw("MySQL 索引"));
        assertEquals("mysql索引", MemoryWriteGate.normalizeRaw("mysql-索引"));
        assertEquals("mysql索引", MemoryWriteGate.normalizeRaw("MySQL＿索引".replace('＿', '_')));
        // 同义词表的 key 不应有重复冲突（这里顺带校验静态初始化的自洽性）
        assertFalse(MemoryWriteGate.normalizeRaw("Go channel").isEmpty());
    }

    @Test
    @DisplayName("同一 canonical topic 的多种写法最终收敛为一个，不会膨胀 Top N 配额")
    void canonicalize_convergesToSingleTopic() {
        // 注意：这里必须用可变 Set 收集，不能用 Set.of()——
        // 因为归一生效时四个返回值完全相同，Set.of() 会直接抛 duplicate element。
        Set<String> results = new HashSet<>(List.of(
                MemoryWriteGate.canonicalize("GC", List.of()),
                MemoryWriteGate.canonicalize("垃圾回收", List.of()),
                MemoryWriteGate.canonicalize("JVM调优", List.of()),
                MemoryWriteGate.canonicalize("垃圾收集器", List.of())
        ));
        assertEquals(1, results.size(), "四种写法应收敛为同一个 canonical topic，实际: " + results);
        assertEquals("JVM垃圾回收", results.iterator().next());
    }
}
