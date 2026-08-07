/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.model.EvaluationReport;
import com.interview.agent.model.InterviewState;
import com.interview.agent.model.QAPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class Evaluator {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String EVALUATOR_PROMPT = """
            你是一位经验丰富的面试评估专家。请根据候选人的完整面试表现，生成一份详细的评估报告。

            请输出纯 JSON 格式：

            {
              "overall_score": 75.0,
              "overall_level": "B",
              "dimension_score": {
                "基础知识": 80.0,
                "项目经验": 70.0,
                "系统设计": 65.0,
                "编程能力": 75.0,
                "沟通表达": 80.0
              },
              "strengths": ["表现优秀的方面1", "方面2"],
              "weaknesses": ["需要提升的方面1", "方面2"],
              "detailed_review": [
                {
                  "question_content": "题目内容",
                  "user_answer": "候选人回答摘要",
                  "score": 75.0,
                  "comment": "点评",
                  "key_points_hit": ["命中要点"],
                  "key_points_missed": ["遗漏要点"]
                }
              ],
              "anomaly_notes": ["观察到的风格/深度突变疑点描述（如有）"],
              "summary": "综合评语（2-3句话）"
            }

            评级标准：
            - A（90-100）：表现出色，强烈推荐
            - B（70-89）：表现良好，推荐
            - C（50-69）：表现一般，需要提升
            - D（0-49）：表现不佳，不推荐

            关于 anomaly_notes（风格一致性观察，非作弊结论）：
            - 请纵览候选人从第一题到最后一题的整体作答风格（用词习惯、回答深度、结构完整度、表达方式）。
            - 如果发现某一题或某几题的回答明显偏离候选人自身的整体风格（例如前几题回答简短、含糊、口语化，
              某一题却突然变成结构完整、术语精确、近乎教科书级的表述；或反过来风格突然大幅下滑），
              请客观描述这个观察到的差异点（指出第几题、差异体现在哪），不要下结论说"作弊"或"抄袭"。
            - 如果整体风格一致、没有明显突变，anomaly_notes 返回空数组 []。
            - 这只是给面试官的一个"风格差异提示"，帮助其在复核时多留意，不是硬性判定。""";

    public EvaluationReport evaluate(InterviewState state, String position,
                                      String candidateName, boolean userTerminated) {
        log.info("[Evaluator] 开始生成评估报告，共 {} 道题", state.getQaHistory().size());

        StringBuilder userMsg = new StringBuilder();
        userMsg.append(String.format("面试岗位：%s\n候选人：%s\n", position,
                candidateName != null ? candidateName : "未知"));

        if (userTerminated) {
            userMsg.append(String.format("注意：候选人在第 %d/%d 题时主动终止了面试，请基于已完成的题目进行评估。\n\n",
                    state.getQaHistory().size(), state.getTotalQuestions()));
        }

        userMsg.append("## 面试记录\n\n");
        for (int i = 0; i < state.getQaHistory().size(); i++) {
            QAPair qa = state.getQaHistory().get(i);
            userMsg.append(String.format("### 第 %d 题\n", i + 1));
            userMsg.append(String.format("**题目**：%s\n", qa.getQuestion().getContent()));
            userMsg.append(String.format("**类型**：%s | **难度**：%s\n", qa.getQuestion().getType(), qa.getQuestion().getDifficulty()));
            userMsg.append(String.format("**候选人回答**：%s\n", qa.getUserAnswer()));
            userMsg.append(String.format("**得分**：%.0f/100\n", qa.getScore()));
            userMsg.append(String.format("**反馈**：%s\n\n", qa.getFeedback()));
        }

        String finalUserMsg = userMsg.toString();
        return AgentUtils.callWithRetry("评估报告生成", AgentUtils.DEFAULT_MAX_ATTEMPTS, () -> {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(EVALUATOR_PROMPT),
                    new UserMessage(finalUserMsg)
            ));

            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            String json = AgentUtils.extractJSON(content);

            try {
                EvaluationReport report = objectMapper.readValue(json, EvaluationReport.class);
                report.setSessionId(state.getSessionId());
                report.setCandidateName(candidateName);
                report.setPosition(position);
                report.setCreatedAt(LocalDateTime.now());
                log.info("[Evaluator] 评估完成，综合得分: {}, 评级: {}", report.getOverallScore(), report.getOverallLevel());
                return report;
            } catch (Exception e) {
                throw new RuntimeException("评估报告解析失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 格式化评估报告为 Markdown（与 Go 版本 FormatReport 对齐）
     */
    public static String formatReport(EvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# 面试评估报告\n\n"));
        sb.append(String.format("**候选人**：%s\n", report.getCandidateName() != null ? report.getCandidateName() : "未知"));
        sb.append(String.format("**面试岗位**：%s\n", report.getPosition()));
        sb.append(String.format("**综合得分**：%.0f/100（%s）\n\n", report.getOverallScore(), report.getOverallLevel()));

        if (report.getDimensionScore() != null && !report.getDimensionScore().isEmpty()) {
            sb.append("## 各维度得分\n\n");
            sb.append("| 维度 | 得分 |\n|------|------|\n");
            report.getDimensionScore().forEach((dim, score) ->
                    sb.append(String.format("| %s | %.0f |\n", dim, score)));
            sb.append("\n");
        }

        if (report.getStrengths() != null && !report.getStrengths().isEmpty()) {
            sb.append("## 优势\n\n");
            report.getStrengths().forEach(s -> sb.append("- ").append(s).append("\n"));
            sb.append("\n");
        }

        if (report.getWeaknesses() != null && !report.getWeaknesses().isEmpty()) {
            sb.append("## 待提升\n\n");
            report.getWeaknesses().forEach(w -> sb.append("- ").append(w).append("\n"));
            sb.append("\n");
        }

        if (report.getDetailedReview() != null && !report.getDetailedReview().isEmpty()) {
            sb.append("## 逐题点评\n\n");
            for (int i = 0; i < report.getDetailedReview().size(); i++) {
                EvaluationReport.QuestionReview qr = report.getDetailedReview().get(i);
                sb.append(String.format("### 第 %d 题（%.0f分）\n\n", i + 1, qr.getScore()));
                sb.append(String.format("**题目**：%s\n\n", qr.getQuestionContent()));
                sb.append(String.format("**点评**：%s\n\n", qr.getComment()));
            }
        }

        if (report.getAnomalyNotes() != null && !report.getAnomalyNotes().isEmpty()) {
            sb.append("## ⚠️ 风险提示（风格一致性观察，仅供参考）\n\n");
            report.getAnomalyNotes().forEach(n -> sb.append("- ").append(n).append("\n"));
            sb.append("\n_以上为 AI 观察到的作答风格差异提示，不代表作弊结论，仅供面试官复核时参考。_\n\n");
        }

        if (report.getSummary() != null) {
            sb.append("## 综合评语\n\n").append(report.getSummary()).append("\n");
        }

        return sb.toString();
    }
}
