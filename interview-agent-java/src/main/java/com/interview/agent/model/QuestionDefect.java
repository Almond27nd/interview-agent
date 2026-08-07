/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B4：一条"题目缺陷"记录——出题 Agent 的草稿被打回时的具体理由。
 * <p>
 * 缺陷有两个来源，都归一化成同一个结构，方便合并后统一回喂给出题 Agent：
 * <ul>
 *   <li>{@link Origin#RULE}：规则预检层（{@code QuestionRuleChecker}）产出，纯 Java 判定，不花 token，
 *       只覆盖机械可判定的硬错误（难度不一致、追问缺失、文本近重复等），零误判；</li>
 *   <li>{@link Origin#CRITIC}：审题 Agent（{@code QuestionReviewer}）产出，语义层面的质量批判
 *       （考点是否跑偏、难度是否名不副实、追问是否递进、事实是否可疑、跨方向是否语义重复）。</li>
 * </ul>
 * 这样拆分的意义：能确定性判定的问题绝不交给模型（省钱且不会误判），只把真正需要"理解"的
 * 判断留给 Critic，两边的职责与 prompt 都更干净。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDefect {

    /** 缺陷来源 */
    public enum Origin {
        /** 规则预检层判定（纯 Java，确定性） */
        RULE,
        /** 审题 Agent 判定（语义层面） */
        CRITIC
    }

    /**
     * 该缺陷指向的方向下标（组内下标，与出题 Agent 输出的 direction_index 对齐）。
     * 用它作为 join key 精确定位到某一道题——这也是为什么 Agent 间协作走结构化数据
     * 而不是自然语言消息：模型说"第三道题有问题"时无法确定它数的是哪一道。
     */
    private int directionIndex;

    /** 缺陷来源 */
    private Origin origin;

    /** 具体问题描述（会原样回喂给出题 Agent，因此要写得可执行） */
    private String reason;

    /** 改进建议（可为空；Critic 通常会给，规则层多为空） */
    private String suggestion;

    public static QuestionDefect ofRule(int directionIndex, String reason) {
        return new QuestionDefect(directionIndex, Origin.RULE, reason, "");
    }

    public static QuestionDefect ofCritic(int directionIndex, String reason, String suggestion) {
        return new QuestionDefect(directionIndex, Origin.CRITIC, reason,
                suggestion == null ? "" : suggestion);
    }
}
