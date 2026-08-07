/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReport {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("candidate_name")
    private String candidateName;

    private String position;

    @JsonProperty("overall_score")
    private double overallScore;

    @JsonProperty("overall_level")
    private String overallLevel;    // A/B/C/D

    @JsonProperty("dimension_score")
    private Map<String, Double> dimensionScore;

    private List<String> strengths;
    private List<String> weaknesses;

    @JsonProperty("detailed_review")
    private List<QuestionReview> detailedReview;

    /**
     * 风格一致性风险提示（非硬性作弊判定）：若某题回答与候选人整体作答风格/深度出现明显突变
     * （如前几题答得含糊，某题突然是教科书级完整表述），LLM 会在此列出观察到的疑点描述。
     * 无异常时为空列表，报告里不展示"风险提示"板块。
     */
    @JsonProperty("anomaly_notes")
    private List<String> anomalyNotes;

    private String summary;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionReview {
        @JsonProperty("question_content")
        private String questionContent;

        @JsonProperty("user_answer")
        private String userAnswer;

        private double score;
        private String comment;

        @JsonProperty("key_points_hit")
        private List<String> keyPointsHit;

        @JsonProperty("key_points_missed")
        private List<String> keyPointsMissed;
    }
}
