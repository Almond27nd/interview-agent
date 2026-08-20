/**
 */
package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JDAnalysis {

    @JsonProperty("raw_jd")
    private String rawJD;

    private String position;
    private String company;

    @JsonProperty("required_skills")
    private List<SkillItem> requiredSkills;

    @JsonProperty("preferred_skills")
    private List<SkillItem> preferredSkills;

    @JsonProperty("experience_level")
    private String experienceLevel;       // junior/mid/senior

    private List<String> responsibilities;

    @JsonProperty("key_topics")
    private List<String> keyTopics;

    private Map<String, String> extra;

    /** LLM 自评：当前 JD 信息是否足够支撑后续简历匹配/出题分析。缺省（旧数据/无该字段）视为 true，不影响历史行为。 */
    @JsonProperty("sufficient")
    @Builder.Default
    private boolean sufficient = true;

    /** 信息不足时，LLM 指出具体缺什么（如"技术栈未明确""未说明职级"），供前端/日志展示 */
    @JsonProperty("missing_info")
    private List<String> missingInfo;

    /** 信息不足时，可直接抛给用户的追问文案 */
    @JsonProperty("clarify_question")
    private String clarifyQuestion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillItem {
        private String name;
        private String category;    // language/framework/database/cloud/other
        private String importance;  // must/preferred
    }
}
