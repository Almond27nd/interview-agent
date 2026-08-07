/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.interview.agent.model.QuestionDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DirectionQuotaChecker} 单元测试。
 *
 * <p>本类守住的核心性质是：<b>Phase 1 的配额违约不能静默通过</b>。
 * 重点覆盖三类 LLM 真实违约形态（字段非法 / 某档超额 / 某档缺量），
 * 以及「超额与缺量同时存在」这个最容易算错缺口的组合场景。
 */
class DirectionQuotaCheckerTest {

    private static QuestionDirection dir(String topic, String type, String difficulty) {
        return QuestionDirection.builder()
                .topic(topic).type(type).difficulty(difficulty)
                .searchQuery(topic).skills(new ArrayList<>(List.of(topic))).context("")
                .build();
    }

    /** 按配额铺满，产出一份完全达标的方向列表。 */
    private static List<QuestionDirection> fullQuotaDirs() {
        List<QuestionDirection> dirs = new ArrayList<>();
        for (String type : DirectionQuotaChecker.TYPES) {
            for (String diff : DirectionQuotaChecker.DIFFICULTIES) {
                int n = DirectionQuotaChecker.expected(type, diff);
                for (int i = 0; i < n; i++) {
                    dirs.add(dir(type + "-" + diff + "-" + i, type, diff));
                }
            }
        }
        return dirs;
    }

    // ==================== 配额表本身 ====================

    @Test
    @DisplayName("配额表是唯一事实源：总数由各格子求和得出，且与 Prompt 文案一致")
    void quota_isSingleSourceOfTruth() {
        assertEquals(31, DirectionQuotaChecker.expectedTotal(),
                "basic 15 + experience 12 + design 4 = 31");
        assertEquals(5, DirectionQuotaChecker.expected("basic", "easy"));
        assertEquals(4, DirectionQuotaChecker.expected("experience", "hard"));
        assertEquals(2, DirectionQuotaChecker.expected("design", "medium"));
        assertEquals(0, DirectionQuotaChecker.expected("design", "easy"),
                "design 刻意不设 easy 档");
        assertEquals(0, DirectionQuotaChecker.expected("unknown", "easy"));

        // Prompt 文案必须由配额表生成，避免「改了代码忘了改 Prompt」的双份维护
        String desc = DirectionQuotaChecker.describeQuota();
        assertTrue(desc.contains("总数应为 31 个"), "文案应包含由配额表算出的总数，实际: " + desc);
        assertTrue(desc.contains("basic") && desc.contains("experience") && desc.contains("design"));
        assertFalse(DirectionQuotaChecker.describeSelfCheck().isBlank());
    }

    @Test
    @DisplayName("达标的方向列表：无缺口、无裁剪")
    void fullQuota_hasNoGap() {
        List<QuestionDirection> dirs = fullQuotaDirs();
        assertEquals(31, dirs.size());
        assertTrue(DirectionQuotaChecker.diff(dirs).isEmpty(), "铺满后不应有缺口");
        assertEquals(31, DirectionQuotaChecker.trimOverflow(dirs).size(), "达标时不应裁剪");
    }

    // ==================== ① normalize：字段非法 ====================

    @Test
    @DisplayName("剔除非法 difficulty —— 否则 QuestionPool 会把它静默归入 medium 桶")
    void normalize_dropsIllegalDifficulty() {
        List<QuestionDirection> dirs = new ArrayList<>(List.of(
                dir("正常题", "basic", "easy"),
                dir("中文难度", "basic", "简单"),
                dir("英文别名", "basic", "middle"),
                dir("空难度", "basic", null)));

        List<QuestionDirection> kept = DirectionQuotaChecker.normalize(dirs);

        assertEquals(1, kept.size(),
                "只有合法 difficulty 应保留，实际: " + kept.stream().map(QuestionDirection::getTopic).toList());
        assertEquals("正常题", kept.get(0).getTopic());
    }

    @Test
    @DisplayName("剔除非法 type、topic 缺失、以及配额外组合（design/easy）")
    void normalize_dropsIllegalTypeAndBlankTopicAndOutOfQuotaCell() {
        List<QuestionDirection> dirs = new ArrayList<>(List.of(
                dir("正常题", "experience", "medium"),
                dir("非法type", "coding", "easy"),
                dir("  ", "basic", "easy"),
                dir("配额外组合", "design", "easy")));

        List<QuestionDirection> kept = DirectionQuotaChecker.normalize(dirs);

        assertEquals(1, kept.size(),
                "实际保留: " + kept.stream().map(QuestionDirection::getTopic).toList());
        assertEquals("正常题", kept.get(0).getTopic());
    }

    @Test
    @DisplayName("大小写/空白差异做容错归一，不因书写差异丢弃内容可能很好的方向")
    void normalize_lowercasesInsteadOfDropping() {
        List<QuestionDirection> kept = DirectionQuotaChecker.normalize(
                new ArrayList<>(List.of(dir("大写", " Basic ", "EASY"))));

        assertEquals(1, kept.size());
        assertEquals("basic", kept.get(0).getType());
        assertEquals("easy", kept.get(0).getDifficulty());
    }

    @Test
    @DisplayName("normalize 对 null / 空列表安全")
    void normalize_nullSafe() {
        assertTrue(DirectionQuotaChecker.normalize(null).isEmpty());
        assertTrue(DirectionQuotaChecker.normalize(new ArrayList<>()).isEmpty());
        assertTrue(DirectionQuotaChecker.diff(null).size() > 0, "null 输入应算作全部缺口");
    }

    // ==================== ② trimOverflow：某档超额 ====================

    @Test
    @DisplayName("超额格子被裁剪，保留靠前的")
    void trimOverflow_cutsExcess() {
        List<QuestionDirection> dirs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            dirs.add(dir("basic-hard-" + i, "basic", "hard"));
        }

        List<QuestionDirection> kept = DirectionQuotaChecker.trimOverflow(dirs);

        assertEquals(5, kept.size(), "basic/hard 配额为 5");
        assertEquals("basic-hard-0", kept.get(0).getTopic(), "应保留靠前的");
        assertEquals("basic-hard-4", kept.get(4).getTopic());
    }

    // ==================== ③ diff：逐格比对 ====================

    @Test
    @DisplayName("【核心】只查总数抓不住违约：31 条但全堆在 hard，必须被逐格比对识别")
    void diff_detectsSkewedDistributionEvenWhenTotalIsCorrect() {
        // 构造总数正好 31、但分布严重歪斜的列表：basic 15 条全是 hard
        List<QuestionDirection> dirs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            dirs.add(dir("basic-hard-" + i, "basic", "hard"));
        }
        for (int i = 0; i < 12; i++) {
            dirs.add(dir("exp-medium-" + i, "experience", "medium"));
        }
        for (int i = 0; i < 4; i++) {
            dirs.add(dir("design-hard-" + i, "design", "hard"));
        }
        assertEquals(DirectionQuotaChecker.expectedTotal(), dirs.size(), "总数刻意做成正好达标");

        Map<String, Integer> gap = DirectionQuotaChecker.diff(dirs);

        assertFalse(gap.isEmpty(), "总数达标但分布歪斜，必须被识别为违约");
        assertEquals(5, gap.get("basic/easy"), "basic/easy 一条都没有");
        assertEquals(5, gap.get("basic/medium"));
        assertEquals(4, gap.get("experience/easy"));
        assertEquals(2, gap.get("design/medium"));
        assertNull(gap.get("basic/hard"), "超额的格子不应出现在缺口里");
    }

    @Test
    @DisplayName("【核心】必须先裁再算缺口：超额与缺量同时存在时，补全后总数才能精确收敛")
    void trimThenDiff_convergesToExactQuota() {
        // hard 多 4 条、easy 少 5 条同时存在（LLM 最常见的违约形态）
        List<QuestionDirection> dirs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            dirs.add(dir("basic-hard-" + i, "basic", "hard"));   // 配额 5，多 4
        }
        for (int i = 0; i < 5; i++) {
            dirs.add(dir("basic-medium-" + i, "basic", "medium"));
        }
        // basic/easy 完全缺失

        List<QuestionDirection> trimmed = DirectionQuotaChecker.trimOverflow(dirs);
        Map<String, Integer> gap = DirectionQuotaChecker.diff(trimmed);

        assertEquals(5, gap.get("basic/easy"),
                "先裁掉超额的 hard 后，easy 缺口应仍是完整的 5，不能被超额抵消");

        // 模拟补全：按缺口精确补齐 basic 的三档
        List<QuestionDirection> filled = new ArrayList<>(trimmed);
        for (int i = 0; i < 5; i++) {
            filled.add(dir("basic-easy-" + i, "basic", "easy"));
        }
        List<QuestionDirection> finalDirs = DirectionQuotaChecker.trimOverflow(filled);

        assertEquals(15, finalDirs.size(), "basic 三档共 15，补全后应精确收敛而非超标");
        assertTrue(DirectionQuotaChecker.diff(finalDirs).keySet().stream()
                        .noneMatch(k -> k.startsWith("basic/")),
                "basic 各档应全部达标");
    }

    @Test
    @DisplayName("缺口描述可读，且能取出涉及的 type（用于决定是否强调「严禁杜撰简历」）")
    void describeGapAndGapTypes() {
        Map<String, Integer> gap = DirectionQuotaChecker.diff(new ArrayList<>());

        assertNotEquals("无", DirectionQuotaChecker.describeGap(gap));
        assertTrue(DirectionQuotaChecker.describeGap(gap).contains("还需"));
        assertEquals("无", DirectionQuotaChecker.describeGap(Map.of()));

        assertTrue(DirectionQuotaChecker.gapTypes(gap).contains("experience"),
                "空列表的缺口应覆盖 experience，从而触发防杜撰提示");
        assertTrue(DirectionQuotaChecker.gapTypes(null).isEmpty());
    }

    // ==================== ④ dedup：语义等价去重 ====================

    @Test
    @DisplayName("【核心】去重不能用字符串 equals：换个写法的同一考点必须被识别")
    void dedup_usesEntityNormalizationNotStringEquals() {
        List<QuestionDirection> existing = List.of(dir("MySQL索引", "basic", "easy"));
        List<QuestionDirection> incoming = new ArrayList<>(List.of(
                dir("mysql 索引优化", "basic", "easy"),   // 同义词表映射到 MySQL索引
                dir("索引失效", "basic", "easy"),          // 同上
                dir("Go channel", "basic", "easy")));     // 真正的新方向

        List<QuestionDirection> kept = DirectionQuotaChecker.dedup(incoming, existing);

        assertEquals(1, kept.size(),
                "同义写法应被判为重复，实际保留: "
                        + kept.stream().map(QuestionDirection::getTopic).toList());
        assertEquals("Go channel", kept.get(0).getTopic());
    }

    @Test
    @DisplayName("补全结果内部也要去重（模型可能在同一次响应里重复给同一考点）")
    void dedup_removesDuplicatesWithinIncoming() {
        List<QuestionDirection> incoming = new ArrayList<>(List.of(
                dir("JVM垃圾回收", "basic", "hard"),
                dir("gc调优", "basic", "hard")));   // 同义词表映射到 JVM垃圾回收

        List<QuestionDirection> kept = DirectionQuotaChecker.dedup(incoming, List.of());

        assertEquals(1, kept.size(), "同一批次内的语义重复也应剔除");
    }

    @Test
    @DisplayName("dedup 对 null 安全")
    void dedup_nullSafe() {
        assertTrue(DirectionQuotaChecker.dedup(null, List.of()).isEmpty());
        assertEquals(1, DirectionQuotaChecker.dedup(
                new ArrayList<>(List.of(dir("A", "basic", "easy"))), null).size());
    }

    // ==================== ⑤ covers：薄弱点覆盖率统计 ====================

    @Test
    @DisplayName("覆盖率统计：topic 与 skills 都参与匹配，且走归一化比对")
    void covers_matchesByTopicOrSkills() {
        List<QuestionDirection> dirs = List.of(
                dir("MySQL索引结构与回表", "basic", "easy"),
                QuestionDirection.builder()
                        .topic("并发场景下的数据一致性")
                        .type("design").difficulty("hard")
                        .skills(new ArrayList<>(List.of("分布式事务")))
                        .searchQuery("").context("")
                        .build());

        assertTrue(DirectionQuotaChecker.covers(dirs, "MySQL索引"),
                "topic 包含目标薄弱点应算覆盖");
        assertTrue(DirectionQuotaChecker.covers(dirs, "分布式事务"),
                "skills 命中也应算覆盖");
        assertTrue(DirectionQuotaChecker.covers(dirs, "seata"),
                "经同义词表归一后 seata → 分布式事务，应算覆盖");
        assertFalse(DirectionQuotaChecker.covers(dirs, "Flink窗口"),
                "完全无关的薄弱点不应算覆盖");
        assertFalse(DirectionQuotaChecker.covers(dirs, null));
        assertFalse(DirectionQuotaChecker.covers(null, "MySQL索引"));
    }
}
