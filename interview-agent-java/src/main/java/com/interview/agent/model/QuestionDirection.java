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
public class QuestionDirection {

    private String topic;
    private String type;        // basic/experience/design
    private String difficulty;  // easy/medium/hard

    @JsonProperty("search_query")
    private String searchQuery;

    private List<String> skills;
    private String context;     // 简历中相关上下文（experience 类必填）
}
