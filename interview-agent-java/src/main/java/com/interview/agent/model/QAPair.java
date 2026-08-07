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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QAPair {

    private PlannedQuestion question;

    @JsonProperty("user_answer")
    private String userAnswer;

    private double score;
    private String feedback;

    @JsonProperty("follow_up_used")
    private boolean followUpUsed;
}
