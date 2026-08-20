/**
 */
package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    private String id;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("jd_analysis")
    private JDAnalysis jdAnalysis;

    private Resume resume;

    @JsonProperty("match_result")
    private ResumeMatchResult matchResult;

    @JsonProperty("question_plan")
    private QuestionPlan questionPlan;

    @JsonProperty("interview_state")
    private InterviewState interviewState;

    private EvaluationReport report;

    @JsonProperty("review_plan")
    private ReviewPlan reviewPlan;

    private String status;  // init/jd_analyzed/resume_matched/planned/interviewing/terminated/evaluated/completed

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    /** 会话状态常量 */
    public static final String STATUS_INIT = "init";
    public static final String STATUS_JD_ANALYZED = "jd_analyzed";
    public static final String STATUS_RESUME_MATCHED = "resume_matched";
    public static final String STATUS_PLANNED = "planned";
    public static final String STATUS_INTERVIEWING = "interviewing";
    public static final String STATUS_TERMINATED = "terminated";
    public static final String STATUS_EVALUATED = "evaluated";
    public static final String STATUS_COMPLETED = "completed";
}
