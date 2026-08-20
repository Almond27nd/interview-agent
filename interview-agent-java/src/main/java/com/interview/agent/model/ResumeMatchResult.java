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
public class ResumeMatchResult {

    @JsonProperty("overall_score")
    private double overallScore;

    @JsonProperty("skill_match")
    private List<SkillMatch> skillMatch;

    private List<String> strengths;
    private List<String> weaknesses;

    @JsonProperty("focus_areas")
    private List<String> focusAreas;

    @JsonProperty("resume_gaps")
    private List<String> resumeGaps;

    /**
     * LLM 自评：当前简历内容是否足够支撑匹配分析。缺省（旧数据/无该字段）视为 true，不影响历史行为。
     * <p>
     * 与 {@code WebSocketHandler.validateInterviewInput} 的长度校验不是同一层——长度校验只挡掉
     * "明显过短"这类可以规则识别的输入；这里覆盖的是长度达标但内容本身无效的情形，例如 PDF/扫描件
     * 解析出乱码、简历只有姓名联系方式没有任何工作经历/项目/技能等，这类问题只有 LLM 读完内容才能判断。
     */
    @JsonProperty("sufficient")
    @Builder.Default
    private boolean sufficient = true;

    /** 信息不足时，LLM 指出具体缺什么（如"无工作经历描述""内容疑似乱码"），供前端/日志展示 */
    @JsonProperty("missing_info")
    private List<String> missingInfo;

    /** 信息不足时，可直接抛给用户的追问文案 */
    @JsonProperty("clarify_question")
    private String clarifyQuestion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMatch {
        @JsonProperty("skill_name")
        private String skillName;

        private boolean required;
        private boolean matched;

        @JsonProperty("match_score")
        private double matchScore;

        private String evidence;
    }
}
