/**
 */
package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewState {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("current_question")
    private int currentQuestion;

    @JsonProperty("total_questions")
    private int totalQuestions;

    @JsonProperty("current_difficulty")
    @Builder.Default
    private String currentDifficulty = "medium"; // easy/medium/hard

    @JsonProperty("consecutive_right")
    private int consecutiveRight;

    @JsonProperty("consecutive_wrong")
    private int consecutiveWrong;

    @JsonProperty("qa_history")
    @Builder.Default
    private List<QAPair> qaHistory = new ArrayList<>();

    @JsonProperty("candidate_profile")
    private String candidateProfile;
}
