/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.skill;

/**
 * Skill 技能接口（与 Go 版本一致）
 * Skill 是介于 Tool（MCP 无状态工具）和 Agent（DAG 预编排流程）之间的能力层级：
 * 有状态、多轮交互、用户意图动态触发、可插拔
 */
public interface Skill {

    /** 技能名称（唯一标识） */
    String name();

    /** 技能描述（用于帮助信息展示） */
    String description();

    /** 判断用户输入是否触发该 Skill */
    boolean match(String input);

    /** 处理一轮交互 */
    SkillResponse handle(String input, SkillState state);
}
