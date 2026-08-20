/**
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
public class PlannedQuestion {

    private String id;
    private String content;
    private String type;        // basic/experience/design
    private String difficulty;  // easy/medium/hard
    private List<String> skills;

    @JsonProperty("follow_ups")
    private List<String> followUps;

    private String reference;
    private String source;      // 题库原题ID 或 "llm"
}
