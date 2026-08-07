/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.interview.agent.model.ReviewPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReviewPlanner 解析逻辑的单元测试。
 * 重点验证：当 LLM 返回的 JSON 缺字段时，复习计划解析不再抛 NPE（对应实测面试中遇到的偶发崩溃）。
 */
class ReviewPlannerTest {

    /**
     * 复现真实场景：LLM 只返回 study_plan，缺 weak_areas 和 resources。
     * 修复前 plan.getWeakAreas().size() 会抛 NullPointerException；
     * 修复后三个列表被规范化为空集合，解析正常返回。
     */
    @Test
    @DisplayName("LLM 漏字段时应规范化为空集合且不抛 NPE")
    void parsePlan_whenLlmOmitsFields_normalizesToEmptyAndNoNpe() {
        ReviewPlanner planner = new ReviewPlanner(null); // parsePlan 不使用 chatModel
        String llmOutput = "{\"study_plan\":[{\"topic\":\"Go 并发\",\"objective\":\"掌握 sync 包\","
                + "\"actions\":[\"读 sync 源码\"],\"time_estimate\":\"4h\"}]}";

        ReviewPlan plan = assertDoesNotThrow(() -> planner.parsePlan(llmOutput, "sess-1"));

        assertNotNull(plan.getWeakAreas(), "weakAreas 应被规范化为非 null");
        assertNotNull(plan.getStudyPlan(), "studyPlan 应非 null");
        assertNotNull(plan.getResources(), "resources 应被规范化为非 null");
        assertEquals(0, plan.getWeakAreas().size());
        assertEquals(0, plan.getResources().size());
        assertEquals(1, plan.getStudyPlan().size());
        assertEquals("sess-1", plan.getSessionId());
        assertNotNull(plan.getCreatedAt());
    }

    /**
     * 极端情况：LLM 返回空对象 {}，三个列表全缺。也应不崩、全部为空集合。
     */
    @Test
    @DisplayName("空 JSON 对象也应安全返回空计划")
    void parsePlan_emptyObject_returnsEmptyPlan() {
        ReviewPlanner planner = new ReviewPlanner(null);

        ReviewPlan plan = assertDoesNotThrow(() -> planner.parsePlan("{}", "sess-empty"));

        assertNotNull(plan.getWeakAreas());
        assertNotNull(plan.getStudyPlan());
        assertNotNull(plan.getResources());
        assertEquals(0, plan.getWeakAreas().size());
    }

    /**
     * 正常完整 JSON 应被正确解析（确保重构没破坏正常路径）。
     */
    @Test
    @DisplayName("完整 JSON 应被正确解析")
    void parsePlan_fullJson_parsedCorrectly() {
        ReviewPlanner planner = new ReviewPlanner(null);
        String llmOutput = "{\"weak_areas\":[{\"topic\":\"MySQL 索引\",\"score\":55.0,\"priority\":\"high\"}],"
                + "\"study_plan\":[{\"topic\":\"索引优化\",\"objective\":\"理解 B+ 树\","
                + "\"actions\":[\"看执行计划\"],\"time_estimate\":\"3h\"}],"
                + "\"resources\":[{\"title\":\"MySQL 官方文档\",\"type\":\"article\",\"url\":\"https://dev.mysql.com\",\"desc\":\"权威\"}]}";

        ReviewPlan plan = planner.parsePlan(llmOutput, "sess-2");

        assertEquals(1, plan.getWeakAreas().size());
        assertEquals("MySQL 索引", plan.getWeakAreas().get(0).getTopic());
        assertEquals("high", plan.getWeakAreas().get(0).getPriority());
        assertEquals(1, plan.getStudyPlan().size());
        assertEquals(1, plan.getResources().size());
        assertEquals("MySQL 官方文档", plan.getResources().get(0).getTitle());
    }
}
