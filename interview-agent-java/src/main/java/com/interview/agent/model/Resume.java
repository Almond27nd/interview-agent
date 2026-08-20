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
public class Resume {

    @JsonProperty("raw_text")
    private String rawText;

    private String name;
    private List<Education> education;
    private List<WorkExp> experience;
    private List<String> skills;
    private List<Project> projects;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Education {
        private String school;
        private String degree;  // bachelor/master/phd
        private String major;
        private String year;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkExp {
        private String company;
        private String title;
        private String duration;
        private String description;

        @JsonProperty("tech_stack")
        private List<String> techStack;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Project {
        private String name;
        private String role;
        private String description;

        @JsonProperty("tech_stack")
        private List<String> techStack;

        private List<String> highlights;
    }
}
