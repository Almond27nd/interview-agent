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

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerScore {

    private double score;
    private String feedback;

    @JsonProperty("key_points_hit")
    private List<String> keyPointsHit;

    @JsonProperty("key_points_missed")
    private List<String> keyPointsMissed;

    @JsonProperty("should_follow_up")
    private boolean shouldFollowUp;
}
