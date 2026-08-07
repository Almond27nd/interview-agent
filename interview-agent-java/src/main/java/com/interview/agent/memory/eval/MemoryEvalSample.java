/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 记忆召回评估的一条样本。
 *
 * <p><b>与题库检索评估（{@code rag.eval.EvalSample}）的本质区别</b>：
 * <table border="1">
 *   <tr><th></th><th>题库检索评估</th><th>记忆召回评估（本类）</th></tr>
 *   <tr><td>query</td><td>一个检索关键词</td><td>一个岗位的<b>技能栈</b>（多个词）</td></tr>
 *   <tr><td>被检索的集合</td><td>题库（成千上万条，外部语料）</td><td>该用户的<b>候选薄弱点</b>（10 条量级，用户自身数据）</td></tr>
 *   <tr><td>标注的黄金标准</td><td>哪些题目相关</td><td>哪些薄弱点<b>本场应当重点考察</b></td></tr>
 *   <tr><td>评什么</td><td>能否找到相关题</td><td>能否把「与本岗位相关且最该复考」的薄弱点排到前面</td></tr>
 * </table>
 * 因此不能复用 {@code EvalSample}——它没有「候选集」这个概念（题库是全局的），
 * 而记忆召回的候选集是<b>每个样本各自不同的</b>（不同用户画像不同）。
 *
 * <p>标注文件示例（{@code data/eval/memory_dataset_v1.json}）：
 * <pre>
 * {
 *   "id": "mem_001",
 *   "position": "Java后端开发工程师",
 *   "jd_skills": ["seata", "两阶段提交", "mysql", "spring cloud"],
 *   "candidates": [
 *     {"topic": "分布式事务", "score": 55, "hit_count": 3, "wrong_count": 3, "stubborn": false, "days_ago": 10, "difficulty": "medium"},
 *     {"topic": "MySQL索引",  "score": 58, "hit_count": 6, "wrong_count": 5, "stubborn": true,  "days_ago": 5,  "difficulty": "medium"},
 *     {"topic": "Flink窗口",  "score": 52, "hit_count": 2, "wrong_count": 2, "stubborn": false, "days_ago": 20, "difficulty": "medium"}
 *   ],
 *   "relevant_topics": ["分布式事务", "MySQL索引"],
 *   "note": "Seata/两阶段提交与分布式事务是同一考点，字符串不匹配"
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryEvalSample {

    /** 样本唯一 ID，如 "mem_001" */
    private String id;

    /** 岗位名称（作为语义 query 的领域上下文） */
    private String position;

    /** 该岗位 JD 的技能关键词（小写） */
    @JsonProperty("jd_skills")
    private List<String> jdSkills;

    /** 候选薄弱点（模拟某个用户画像在本场面试前的状态） */
    private List<CandidateSpec> candidates;

    /**
     * 人工标注的黄金标准：哪些 topic 本场<b>应当</b>被判为强相关并优先考察。
     * 顺序不重要（用集合语义），排序质量由 MRR 与 Recall@K 体现。
     */
    @JsonProperty("relevant_topics")
    private List<String> relevantTopics;

    /** 标注说明（记录为什么这么标，便于复核） */
    private String note;

    /**
     * 候选薄弱点的声明式描述。
     * <p>用「相对天数」而不是绝对时间，这样数据集不会随时间推移而失效——
     * 评估时按 {@code now - daysAgo} 还原成 {@code Evidence}，保证结果可复现。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CandidateSpec {

        /** 薄弱点 topic（已归一后的规范名） */
        private String topic;

        /** 最近一次得分 */
        private double score;

        /** 被考察次数 */
        @JsonProperty("hit_count")
        private int hitCount;

        /** 答错次数 */
        @JsonProperty("wrong_count")
        private int wrongCount;

        /** 是否已标记为顽固薄弱点 */
        private boolean stubborn;

        /** 掌握后复发次数 */
        @JsonProperty("relapse_count")
        private int relapseCount;

        /** 距今天数（用于还原时间衰减，保证数据集不随时间失效） */
        @JsonProperty("days_ago")
        @Builder.Default
        private int daysAgo = 5;

        /** 该次考察的题目难度 easy/medium/hard */
        @Builder.Default
        private String difficulty = "medium";

        /** 实体归一时留档的别名（会参与词法匹配） */
        private List<String> aliases;
    }
}
