/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.interview.agent.model.PlannedQuestion;
import com.interview.agent.model.QuestionDefect;
import com.interview.agent.model.QuestionDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B4 规则预检层的单元测试。
 * <p>
 * 这一层的价值恰恰在于"确定性"——所以它必须被测试覆盖：如果规则层出现误判，
 * 会导致本来合格的题目被无谓地回炉重出（白烧 token，还可能越改越差）。
 */
class QuestionRuleCheckerTest {

    private static QuestionDirection dir(String topic, String difficulty) {
        QuestionDirection d = new QuestionDirection();
        d.setTopic(topic);
        d.setType("basic");
        d.setDifficulty(difficulty);
        d.setSkills(List.of(topic));
        return d;
    }

    private static PlannedQuestion q(String content, String difficulty) {
        PlannedQuestion p = new PlannedQuestion();
        p.setContent(content);
        p.setType("basic");
        p.setDifficulty(difficulty);
        p.setFollowUps(new ArrayList<>(List.of("追问一：能再深入说说底层实现吗")));
        p.setReference("参考答案要点");
        p.setSource("q_1");
        return p;
    }

    @Test
    @DisplayName("完全合格的草稿不应产生任何缺陷")
    void check_validDraft_noDefects() {
        List<QuestionDirection> dirs = List.of(
                dir("MySQL 索引优化", "hard"),
                dir("Go channel 原理", "hard"));
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, q("请说明 MySQL 联合索引在什么情况下会失效", "hard"));
        drafts.put(1, q("Go 中无缓冲 channel 的发送与接收是如何同步的", "hard"));

        assertTrue(QuestionRuleChecker.check(dirs, drafts).isEmpty());
    }

    /**
     * 难度标注不一致必须被拦下：StageScheduler 按难度分档抽题，标错会直接破坏
     * 动态难度调节（adjustDifficulty）的梯度效果。
     */
    @Test
    @DisplayName("difficulty 与方向要求不一致应被打回")
    void check_difficultyMismatch_reported() {
        List<QuestionDirection> dirs = List.of(dir("JVM GC 原理", "hard"));
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, q("请简述什么是垃圾回收", "easy"));

        List<QuestionDefect> defects = QuestionRuleChecker.check(dirs, drafts);

        assertEquals(1, defects.size());
        assertEquals(0, defects.get(0).getDirectionIndex());
        assertEquals(QuestionDefect.Origin.RULE, defects.get(0).getOrigin());
        assertTrue(defects.get(0).getReason().contains("hard"));
    }

    @Test
    @DisplayName("缺少 follow_ups / reference / source 应分别被打回")
    void check_missingFields_reported() {
        List<QuestionDirection> dirs = List.of(dir("Redis 持久化", "medium"));
        PlannedQuestion bad = q("请对比 RDB 与 AOF 两种持久化方式的取舍", "medium");
        bad.setFollowUps(new ArrayList<>());
        bad.setReference("");
        bad.setSource("");
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, bad);

        List<QuestionDefect> defects = QuestionRuleChecker.check(dirs, drafts);

        assertEquals(3, defects.size(), "follow_ups / reference / source 三项缺失应各报一条");
        assertTrue(defects.stream().allMatch(d -> d.getOrigin() == QuestionDefect.Origin.RULE));
    }

    @Test
    @DisplayName("题干过短（疑似输出截断）应被打回")
    void check_tooShortContent_reported() {
        List<QuestionDirection> dirs = List.of(dir("Kafka 顺序性", "medium"));
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, q("Kafka?", "medium"));

        List<QuestionDefect> defects = QuestionRuleChecker.check(dirs, drafts);

        assertEquals(1, defects.size());
        assertTrue(defects.get(0).getReason().contains("过短"));
    }

    /**
     * 核心场景：题库检索是逐方向独立进行的，邻近方向很容易命中同一道原题。
     * 这类"同一道题被两个方向同时用上"是出题 Agent 结构上发现不了的问题。
     */
    @Test
    @DisplayName("两个方向出了同一道题应打回后一个方向")
    void check_duplicateContent_reportsLatterIndex() {
        List<QuestionDirection> dirs = List.of(
                dir("MySQL 索引优化", "hard"),
                dir("MySQL B+ 树结构", "hard"));
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, q("请说明 MySQL 联合索引在什么情况下会失效", "hard"));
        drafts.put(1, q("请说明 MySQL 联合索引在什么情况下会失效？", "hard"));

        List<QuestionDefect> defects = QuestionRuleChecker.check(dirs, drafts);

        assertEquals(1, defects.size());
        assertEquals(1, defects.get(0).getDirectionIndex(), "应只打回后一个方向，保留前一个");
        assertTrue(defects.get(0).getReason().contains("重复"));
    }

    /**
     * 分层边界的显式验证：同义换序（"索引失效的场景有哪些" vs "哪些场景会导致索引失效"）
     * 的 bigram Jaccard 相似度约 0.5，低于 0.6 阈值，规则层<b>故意不报</b>。
     * <p>
     * 这不是缺陷而是设计取舍：规则层的定位是"零误判地兜住几乎肯定重复的情况"（如同一道题库
     * 原题被两个方向同时命中），一旦为了抓这种换序把阈值调低，就会开始误伤考点相邻但确实不同的
     * 题目——误判的代价（合格题被白白重出，还可能越改越差）比漏判更高。措辞不同而考点相同的
     * 语义重复，交给有语义理解能力的审题 Agent 判断，这正是两层分工的意义。
     */
    @Test
    @DisplayName("同义换序属于语义重复，规则层不报（交由审题 Agent 判断）")
    void check_reorderedDuplicate_leftToCritic() {
        List<QuestionDirection> dirs = List.of(
                dir("MySQL 索引失效", "medium"),
                dir("MySQL 查询优化", "medium"));
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, q("MySQL 索引失效的场景有哪些", "medium"));
        drafts.put(1, q("有哪些场景会导致 MySQL 索引失效", "medium"));

        assertTrue(QuestionRuleChecker.check(dirs, drafts).isEmpty(),
                "规则层只兜确定性重复，语义换序应留给 Critic，避免降低阈值带来误判");
    }

    /**
     * 反向保证：考点不同的题目绝不能被误判为重复。规则层宁可漏判（交给 Critic 兜），
     * 也不能误判——误判会让合格的题被白白重出。
     */
    @Test
    @DisplayName("考点不同的题目不应被误判为重复")
    void check_distinctQuestions_notReportedAsDuplicate() {
        List<QuestionDirection> dirs = List.of(
                dir("MySQL 索引", "hard"),
                dir("Redis 过期策略", "hard"));
        Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        drafts.put(0, q("请说明 MySQL 联合索引最左前缀原则的底层原因", "hard"));
        drafts.put(1, q("Redis 惰性删除与定期删除如何配合保证内存可控", "hard"));

        assertTrue(QuestionRuleChecker.check(dirs, drafts).isEmpty());
    }

    @Test
    @DisplayName("空草稿或空方向不应抛异常")
    void check_emptyInputs_safe() {
        assertTrue(QuestionRuleChecker.check(null, null).isEmpty());
        assertTrue(QuestionRuleChecker.check(List.of(), new HashMap<>()).isEmpty());
        assertTrue(QuestionRuleChecker.check(List.of(dir("x", "easy")), new HashMap<>()).isEmpty());
    }
}
