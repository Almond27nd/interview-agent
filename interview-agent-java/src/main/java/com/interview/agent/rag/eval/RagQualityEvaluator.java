/**
 */
package com.interview.agent.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.rag.RagDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 LLM 的 RAG 质量评估器（与 Go 版本 rag/evaluation.go 一致）。
 *
 * 提供两类在线评估能力：
 * 1. {@link #evaluate} —— 三维质量评估：忠实度 / 相关性 / 完整性
 * 2. {@link #evaluateQuestionBank} —— 题库诊断：精确率 / 召回率 / 技能覆盖度
 *
 * <p>对应前端 message.ts 的 RAGEvaluation 类型（precision/recall/relevance/
 * completeness/overall/summary/skill_coverage/question_evals）。
 */
@Slf4j
@Component
public class RagQualityEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EVAL_PROMPT = """
            你是一个 RAG（检索增强生成）质量评估专家。请评估以下 RAG 系统的输出质量。

            ## 用户问题
            %s

            ## 检索到的参考文档
            %s

            ## 系统生成的回答
            %s

            请从以下三个维度评分（0-1 之间的小数），并输出纯 JSON：

            {
              "faithfulness": 0.0,
              "relevance": 0.0,
              "completeness": 0.0,
              "reasoning": "评估理由"
            }

            评分标准：
            - faithfulness（忠实度）：回答是否完全基于检索到的文档？是否有臆造内容？
            - relevance（相关性）：检索到的文档是否与用户问题相关？
            - completeness（完整性）：回答是否覆盖了问题的所有方面？是否有遗漏？""";

    private static final String QUESTION_BANK_EVAL_PROMPT = """
            你是一个 RAG 检索质量评估专家。请评估以下从题库中检索到的题目对目标岗位 JD 的匹配质量。

            ## 目标岗位 JD
            %s

            ## 从题库中检索到的题目（共 %d 道）
            %s

            请严格按以下步骤评估，并输出纯 JSON：

            第一步：逐题判断相关性——每道检索到的题目是否与该 JD 的技能要求相关？
            第二步：提取 JD 中的核心技能方向，逐一检查题库是否覆盖
            第三步：计算指标

            {
              "precision": 0.0,
              "recall": 0.0,
              "relevance": 0.0,
              "summary": "给面试者的诊断建议（2-3句话）",
              "skill_coverage": [
                {"skill": "技能名称", "covered": true, "quality": "充足/偏少/缺失"}
              ],
              "question_evals": [
                {"index": 1, "relevant": true, "reason": "简要理由"}
              ]
            }

            指标计算方式：
            - precision（精确率）= 检索到的题目中相关题数 / 检索到的总题数。衡量检索的准确程度。
            - recall（召回率）= 题库覆盖的 JD 技能方向数 / JD 总技能方向数。衡量题库对 JD 的覆盖程度。
            - relevance（语义相关性，0-1）：整体来看，检索结果与 JD 岗位需求的语义匹配程度。
            - question_evals：必须对每道题逐一评估是否与 JD 相关。
            - skill_coverage：必须列出 JD 中的每个核心技能方向。""";

    private final ChatModel chatModel;

    public RagQualityEvaluator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 评估 RAG 输出质量（忠实度 / 相关性 / 完整性）。 */
    public EvalResult evaluate(String question, List<RagDocument> docs, String answer) {
        StringBuilder docsText = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            docsText.append(String.format("[文档%d] %s%n", i + 1, docs.get(i).getContent()));
        }

        String prompt = String.format(EVAL_PROMPT, question, docsText, answer);
        String content = extractJson(chatModel.call(new Prompt(prompt)).getResult().getOutput().getText());

        try {
            RawEval raw = MAPPER.readValue(content, RawEval.class);
            double overall = (raw.faithfulness + raw.relevance + raw.completeness) / 3;
            return EvalResult.builder()
                    .faithfulness(raw.faithfulness)
                    .relevance(raw.relevance)
                    .completeness(raw.completeness)
                    .overall(overall)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("rag_evaluator: parse response: " + e.getMessage(), e);
        }
    }

    /** 评估题库与 JD 的匹配质量（在线题库诊断：精确率 / 召回率 / 技能覆盖度）。 */
    public QuestionBankEvalResult evaluateQuestionBank(String jdSkills, List<RagDocument> docs) {
        StringBuilder docsText = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            docsText.append(String.format("[题目%d] %s%n", i + 1, docs.get(i).getContent()));
        }

        String prompt = String.format(QUESTION_BANK_EVAL_PROMPT, jdSkills, docs.size(), docsText);
        String content = extractJson(chatModel.call(new Prompt(prompt)).getResult().getOutput().getText());

        try {
            QuestionBankEvalResult result = MAPPER.readValue(content, QuestionBankEvalResult.class);
            result.setCompleteness(result.getRecall()); // 保持兼容
            result.setOverall((result.getPrecision() + result.getRecall() + result.getRelevance()) / 3);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("rag_evaluator: parse question bank eval: " + e.getMessage(), e);
        }
    }

    /** 从 LLM 输出中提取第一个完整的 JSON 对象（容忍 ```json 包裹和多余文字）。 */
    private static String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class RawEval {
        private double faithfulness;
        private double relevance;
        private double completeness;
        private String reasoning;
    }

    /** RAG 三维质量评估结果。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvalResult {
        /** 忠实度（0-1）：回答是否基于检索文档 */
        private double faithfulness;
        /** 相关性（0-1）：检索文档是否与问题相关 */
        private double relevance;
        /** 完整性（0-1）：回答是否覆盖了所有要点 */
        private double completeness;
        /** 综合得分 */
        private double overall;
    }

    /** 题库诊断评估结果，字段与前端 RAGEvaluation 对齐。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuestionBankEvalResult {
        /** 精确率：检索到的题中有多少是真正相关的 */
        private double precision;
        /** 召回率（近似）：JD 技能方向被题库覆盖的比例 */
        private double recall;
        /** 语义相关性：检索结果与 JD 的语义匹配程度 */
        private double relevance;
        /** 技能覆盖度：同 recall，保留兼容 */
        private double completeness;
        /** 综合得分 */
        private double overall;
        /** 诊断摘要 */
        private String summary;
        /** 各技能方向覆盖详情 */
        @JsonProperty("skill_coverage")
        private List<SkillCoverage> skillCoverage;
        /** 每道检索题的逐题评估 */
        @JsonProperty("question_evals")
        private List<QuestionEval> questionEvals;
    }

    /** 单个技能方向的覆盖情况。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillCoverage {
        private String skill;
        private boolean covered;
        /** 覆盖质量：充足/偏少/缺失 */
        private String quality;
    }

    /** 单道检索题目的评估。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionEval {
        private int index;
        private boolean relevant;
        private String reason;
    }
}
