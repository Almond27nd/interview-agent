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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewPlan {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("weak_areas")
    private List<WeakArea> weakAreas;

    @JsonProperty("study_plan")
    private List<StudyItem> studyPlan;

    private List<Resource> resources;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeakArea {
        private String topic;
        private double score;
        private String priority;  // high/medium/low
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudyItem {
        private String topic;
        private String objective;
        private List<String> actions;

        @JsonProperty("time_estimate")
        private String timeEstimate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Resource {
        private String title;
        private String type;  // article/video/repo/book
        private String url;
        private String desc;
    }
}
