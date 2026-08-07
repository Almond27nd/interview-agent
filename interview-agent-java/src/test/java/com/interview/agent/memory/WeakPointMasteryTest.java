/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 证据型记忆（M1）的单元测试：掌握度置信度、时间衰减、难度加权、召回优先级。
 * <p>只测纯计算逻辑（{@link UserProfile.WeakPoint#mastery()} / {@code priority()}），
 * 不涉及存储 IO，可独立运行。
 */
class WeakPointMasteryTest {

    private static UserProfile.Evidence ev(double score, String difficulty, long daysAgo) {
        return UserProfile.Evidence.builder()
                .score(score)
                .difficulty(difficulty)
                .askedAt(LocalDateTime.now().minusDays(daysAgo))
                .build();
    }

    private static UserProfile.WeakPoint wp(UserProfile.Evidence... evidences) {
        List<UserProfile.Evidence> list = new ArrayList<>(List.of(evidences));
        return UserProfile.WeakPoint.builder()
                .topic("MySQL索引")
                .evidences(list)
                .aliases(new ArrayList<>())
                .build();
    }

    // ==================== 掌握度置信度 ====================

    @Test
    @DisplayName("无证据时退化为按旧字段 score 估算——保证旧数据也能参与排序")
    void mastery_fallsBackToLegacyScore() {
        UserProfile.WeakPoint legacy = UserProfile.WeakPoint.builder()
                .topic("Redis持久化")
                .score(55)
                .hitCount(1)
                .wrongCount(1)
                .build();
        assertEquals(0.55, legacy.mastery(), 0.01);
    }

    @Test
    @DisplayName("持续低分 → 掌握度低")
    void mastery_consistentlyLow() {
        UserProfile.WeakPoint w = wp(
                ev(50, "medium", 10),
                ev(55, "medium", 5),
                ev(52, "medium", 1));
        assertTrue(w.mastery() < 0.6, "持续低分掌握度应偏低，实际: " + w.mastery());
    }

    @Test
    @DisplayName("【核心】蒙对一道 easy 题不足以判定掌握——难度加权让简单题的说服力打折")
    void mastery_easyCorrectIsNotEnough() {
        // 场景：历史全是低分，最近答对一道 easy 题
        UserProfile.WeakPoint easyWin = wp(
                ev(50, "medium", 20),
                ev(52, "medium", 12),
                ev(48, "medium", 6),
                ev(85, "easy", 0));

        // 同样的历史，但最近答对的是 hard 题
        UserProfile.WeakPoint hardWin = wp(
                ev(50, "medium", 20),
                ev(52, "medium", 12),
                ev(48, "medium", 6),
                ev(85, "hard", 0));

        assertTrue(hardWin.mastery() > easyWin.mastery(),
                "hard 题答对应比 easy 题答对更能提升掌握度");
        assertTrue(easyWin.mastery() < UserProfile.WeakPoint.MASTERY_THRESHOLD,
                "蒙对一道 easy 题不应直接达到掌握阈值，实际: " + easyWin.mastery());
    }

    @Test
    @DisplayName("时间衰减是连续的：久远的低分证据权重更低，不是「30 天二值硬切」")
    void mastery_timeDecayIsContinuous() {
        // 久远低分 + 近期高分 → 掌握度应明显高于「近期低分 + 久远高分」
        UserProfile.WeakPoint improving = wp(
                ev(45, "medium", 60),
                ev(88, "medium", 1));
        UserProfile.WeakPoint regressing = wp(
                ev(88, "medium", 60),
                ev(45, "medium", 1));

        assertTrue(improving.mastery() > regressing.mastery(),
                "近期表现应主导掌握度：improving=" + improving.mastery()
                        + ", regressing=" + regressing.mastery());
    }

    @Test
    @DisplayName("一致性惩罚：时对时错（方差大）的掌握度低于稳定表现")
    void mastery_consistencyPenalty() {
        UserProfile.WeakPoint stable = wp(
                ev(70, "medium", 5),
                ev(72, "medium", 3),
                ev(71, "medium", 1));
        UserProfile.WeakPoint volatile_ = wp(
                ev(35, "medium", 5),
                ev(95, "medium", 3),
                ev(40, "medium", 1));

        assertTrue(stable.mastery() > volatile_.mastery(),
                "稳定表现的掌握度应高于时对时错：stable=" + stable.mastery()
                        + ", volatile=" + volatile_.mastery());
    }

    @Test
    @DisplayName("掌握度始终落在 [0,1] 区间")
    void mastery_bounded() {
        assertTrue(wp(ev(100, "hard", 0)).mastery() <= 1.0);
        assertTrue(wp(ev(0, "easy", 0)).mastery() >= 0.0);
        assertTrue(wp().mastery() >= 0.0);
    }

    // ==================== 召回优先级 ====================

    @Test
    @DisplayName("【核心】反复答错的顽固薄弱点必须排在偶发薄弱点之前——原实现的排序恰好是反的")
    void priority_stubbornBeatsOccasional() {
        // 偶发：考 1 次错 1 次得 55（原实现按最新分排序，它反而更靠前）
        UserProfile.WeakPoint occasional = UserProfile.WeakPoint.builder()
                .topic("Flink窗口")
                .score(55).hitCount(1).wrongCount(1)
                .evidences(new ArrayList<>(List.of(ev(55, "medium", 2))))
                .build();

        // 顽固：考 6 次错 5 次得 58，已标记 stubborn
        UserProfile.WeakPoint stubborn = UserProfile.WeakPoint.builder()
                .topic("MySQL索引")
                .score(58).hitCount(6).wrongCount(5).stubborn(true)
                .evidences(new ArrayList<>(List.of(
                        ev(50, "medium", 40), ev(55, "medium", 30), ev(52, "medium", 20),
                        ev(58, "medium", 10), ev(45, "medium", 5), ev(58, "medium", 1))))
                .build();

        assertTrue(stubborn.priority() > occasional.priority(),
                "顽固薄弱点优先级应更高：stubborn=" + stubborn.priority()
                        + ", occasional=" + occasional.priority());
    }

    @Test
    @DisplayName("复发次数越多，召回优先级越高")
    void priority_relapseIncreasesPriority() {
        UserProfile.WeakPoint once = UserProfile.WeakPoint.builder()
                .topic("A").score(55).hitCount(2).wrongCount(1).relapseCount(0)
                .evidences(new ArrayList<>(List.of(ev(55, "medium", 1))))
                .build();
        UserProfile.WeakPoint relapsed = UserProfile.WeakPoint.builder()
                .topic("A").score(55).hitCount(2).wrongCount(1).relapseCount(2)
                .evidences(new ArrayList<>(List.of(ev(55, "medium", 1))))
                .build();

        assertTrue(relapsed.priority() > once.priority());
    }

    @Test
    @DisplayName("错误率参与决策：同等掌握度下错得更频繁的优先")
    void priority_wrongRateMatters() {
        UserProfile.WeakPoint lowRate = UserProfile.WeakPoint.builder()
                .topic("A").score(55).hitCount(10).wrongCount(1)
                .evidences(new ArrayList<>(List.of(ev(55, "medium", 1))))
                .build();
        UserProfile.WeakPoint highRate = UserProfile.WeakPoint.builder()
                .topic("B").score(55).hitCount(10).wrongCount(9)
                .evidences(new ArrayList<>(List.of(ev(55, "medium", 1))))
                .build();

        assertTrue(highRate.priority() > lowRate.priority(),
                "hitCount/wrongCount 必须真正参与决策，而不只是 Prompt 展示文本");
    }

    // ==================== 双时间轴：软失效 ====================

    @Test
    @DisplayName("软失效语义：masteredAt 非空即视为已掌握、不参与召回，但记录仍在库中")
    void isActive_reflectsSoftInvalidation() {
        UserProfile.WeakPoint active = wp(ev(50, "medium", 1));
        assertTrue(active.isActive());

        active.setMasteredAt(LocalDateTime.now());
        assertFalse(active.isActive(), "已掌握的项不参与召回");

        // 关键：记录本身没有被删除，证据历史完整保留 —— 这才能支撑复发检测
        assertNotNull(active.getEvidences());
        assertEquals(1, active.getEvidences().size(),
                "软失效必须保留历史证据，否则复发时会被当成全新薄弱点");
    }

    @Test
    @DisplayName("证据难度权重：easy < medium < hard")
    void evidence_difficultyWeight() {
        assertEquals(0.7, ev(80, "easy", 0).difficultyWeight(), 1e-9);
        assertEquals(1.0, ev(80, "medium", 0).difficultyWeight(), 1e-9);
        assertEquals(1.3, ev(80, "hard", 0).difficultyWeight(), 1e-9);
        // 难度缺失或非法值时退化为 medium 权重，不抛异常
        assertEquals(1.0, ev(80, null, 0).difficultyWeight(), 1e-9);
        assertEquals(1.0, ev(80, "unknown", 0).difficultyWeight(), 1e-9);
    }
}
