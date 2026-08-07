/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.model.JDAnalysis;
import com.interview.agent.model.Resume;
import com.interview.agent.model.ResumeMatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeMatcher {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RESUME_MATCHER_PROMPT = """
            你是一个专业的简历匹配分析专家。你需要将候选人的简历与目标岗位 JD 进行深度匹配分析。

            请按照以下 JSON 格式输出匹配结果（不要输出其他内容，只输出纯 JSON）：

            {
              "overall_score": 75.0,
              "skill_match": [
                {
                  "skill_name": "技能名称",
                  "required": true,
                  "matched": true,
                  "match_score": 80.0,
                  "evidence": "从简历中找到的匹配证据"
                }
              ],
              "strengths": ["优势1", "优势2"],
              "weaknesses": ["薄弱点1", "薄弱点2"],
              "focus_areas": ["面试重点考察方向1", "面试重点考察方向2"],
              "resume_gaps": ["简历空白点1（可深挖的地方）"],
              "sufficient": true,
              "missing_info": [],
              "clarify_question": ""
            }

            评分标准：
            - overall_score: 0-100 分，综合考虑技能匹配度、经验相关性、项目质量
            - skill_match: 逐项列出 JD 要求的技能，标注是否在简历中匹配到
            - strengths: 候选人明显的优势（与岗位相关的）
            - weaknesses: 候选人的不足或需要提升的地方
            - focus_areas: 基于匹配分析推荐的面试重点考察方向
            - resume_gaps: 简历中可以深挖或追问的空白点
            - sufficient / missing_info / clarify_question 是对本次简历内容有效性的自评（不是长度校验，
              长度过短的输入已在更前置的规则层被挡掉，这里针对的是长度达标但内容本身无效/空洞的情形）：
              1. 如果简历内容疑似乱码（如 PDF/扫描件解析失败产生的无意义字符）、或只有姓名/联系方式等
                 基本信息而完全没有工作经历、项目、技能中的任何一项，导致无法据此做有意义的匹配分析，
                 将 sufficient 设为 false；missing_info 列出具体缺什么（如"内容疑似乱码"、"无工作经历
                 描述"、"无任何技能列表"）；clarify_question 给出一句可直接展示给用户的追问，
                 明确指出需要补充或修正的内容。
              2. 只要简历能大致支撑匹配分析（哪怕经历简短、项目不多），sufficient 设为 true，
                 missing_info 留空数组，clarify_question 留空字符串，其余字段仍尽力填充已知部分。
              3. 不要仅因为简历简短就判定不足——信息密度高的简短简历应视为充分。""";

    public ResumeMatchResult match(JDAnalysis jdAnalysis, Resume resume) {
        log.info("[ResumeMatcher] 开始匹配简历与 JD: {}", jdAnalysis.getPosition());

        String jdSummary = formatJDForMatching(jdAnalysis);
        String resumeText = formatResumeForMatching(resume);
        String userMsg = String.format("## 岗位 JD 分析结果\n%s\n\n## 候选人简历\n%s", jdSummary, resumeText);

        return AgentUtils.callWithRetry("简历匹配", AgentUtils.DEFAULT_MAX_ATTEMPTS, () -> {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(RESUME_MATCHER_PROMPT),
                    new UserMessage(userMsg)
            ));

            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            String json = AgentUtils.extractJSON(content);

            try {
                ResumeMatchResult result = objectMapper.readValue(json, ResumeMatchResult.class);
                log.info("[ResumeMatcher] 匹配完成，综合得分: {}", result.getOverallScore());
                return result;
            } catch (Exception e) {
                throw new RuntimeException("简历匹配结果解析失败: " + e.getMessage(), e);
            }
        });
    }

    private String formatJDForMatching(JDAnalysis jd) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jd);
        } catch (Exception e) {
            return jd.toString();
        }
    }

    private String formatResumeForMatching(Resume resume) {
        if (resume.getRawText() != null && !resume.getRawText().isEmpty()) {
            return resume.getRawText();
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resume);
        } catch (Exception e) {
            return resume.toString();
        }
    }
}
