/**
 */
package com.interview.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.mcp.CandidateProfileTool;
import com.interview.agent.mcp.GitHubTool;
import com.interview.agent.mcp.QuestionBankTool;
import com.interview.agent.mcp.WebScraperTool;
import com.interview.agent.model.EvaluationReport;
import com.interview.agent.model.ReviewPlan;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 复习规划 Agent —— 全项目唯一真正调用外部工具的 Agent，用 Spring AI Alibaba 的 ReactAgent 实现：
 * 模型根据面试评估报告自主决定是否、用什么关键词调用工具来推荐真实开源项目/练习题/校验链接，
 * 再综合产出个性化复习计划（ReactAgent 的 tool-calling 循环，区别于其余单轮 LLM Agent）。
 *
 * B2：生成后加一次纯规则的自校验（高优先级薄弱点是否都被 study_plan 覆盖），漏了就把缺口
 *     作为新的 observation 塞回对话历史，让模型补充，最多 {@link #MAX_REFLECT_ROUNDS} 轮——
 *     这才是 ReAct 论文里"Reason-Act-Observe"该有的样子（而不是只做一次 Act）。
 * B3：从单工具（GitHub 搜索）扩展为多工具智能体（题库检索 / 链接校验 / 候选人历史画像查询）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewPlanner {

    /** B2 反思循环最多重试轮数：规则校验不花 token，只有真的漏了才触发新一轮模型调用 */
    private static final int MAX_REFLECT_ROUNDS = 2;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** GitHub 工具（仅配置了 token 时存在）；存在时作为 ReactAgent 的工具注入 */
    @Setter
    @Autowired(required = false)
    private GitHubTool gitHubTool;

    /** B3：题库检索工具（复用 B1 里给 QuestionPlanner 封装的同一套 Milvus/BM25/Rerank 能力） */
    @Setter
    @Autowired(required = false)
    private QuestionBankTool questionBankTool;

    /** B3：网页链接校验工具（此前只在 WebLoader 里被 Java 代码直接调用，从未暴露给模型自主调用） */
    @Setter
    @Autowired(required = false)
    private WebScraperTool webScraperTool;

    /** B3：候选人历史薄弱点查询工具 */
    @Setter
    @Autowired(required = false)
    private CandidateProfileTool candidateProfileTool;

    // 注意：instruction 会被框架按 f-string 模板渲染（{x} 视为占位符），因此这里不能出现裸 {}。
    // 具体 JSON 输出格式放到用户消息里（用户输入不经模板渲染）。
    private static final String REVIEW_PLANNER_INSTRUCTION = """
            你是一位技术学习路径规划专家，要根据候选人的面试评估报告制定一份个性化的复习计划。

            你可以自主判断是否需要、以什么顺序使用以下工具（可以调用多个，也可以对同一个工具多次调用）：
            - search_github_repos：候选人存在明显薄弱领域、需要推荐真实可用的开源项目或教程时，
              用合适的英文技术关键词调用，type 设为 repo、url 用搜到的真实链接
            - search_practice_questions：需要给某个薄弱点推荐具体的"巩固练习题"时，用中文关键词
              检索候选人专属题库，避免空泛地说"多练习 XX"却给不出真实题目
            - query_candidate_weak_point_history：无需参数；需要判断某个薄弱点是长期反复出现
              （应重点安排学习计划）还是本场偶发失误（可适当降低优先级）时调用
            - verify_url：推荐官方文档/教程等链接前调用，确认链接真实可访问，避免推荐过期/404资源

            也可以补充经典书籍、官方文档等非工具检索到的资源。如果工具不可用或没搜到结果，
            就只用你已知的优质资源，不要编造链接或题目ID。

            规划原则：优先解决高优先级薄弱点；每个学习项给出可执行的具体行动；推荐资源实用、高质量；时间估算合理。
            如果提供了"上一次复习计划"（候选人此前针对同一岗位面试过）：
              - 对比上次的薄弱领域，若本次报告显示已改善/不再是薄弱点，不要再重复推荐上次的学习任务
              - 若薄弱领域依旧存在，可以保留/深化上次的学习计划，避免推荐完全重复的资源链接
            最终只输出用户要求的那个纯 JSON 对象，不要输出任何工具调用过程、思考说明或多余文字。""";

    // 用户消息里携带报告与 JSON 结构要求（含 {}，但用户输入不被模板渲染，安全）。
    private static final String OUTPUT_FORMAT = """

            请严格按以下 JSON 格式输出（只输出 JSON）：
            {
              "weak_areas": [
                {"topic": "薄弱领域名称", "score": 50.0, "priority": "high/medium/low"}
              ],
              "study_plan": [
                {"topic": "学习主题", "objective": "学习目标", "actions": ["具体行动1", "具体行动2"], "time_estimate": "预估时间"}
              ],
              "resources": [
                {"title": "资源标题", "type": "article/video/repo/book", "url": "链接（如有）", "desc": "推荐理由"}
              ]
            }""";

    /**
     * @param report            本场面试的评估报告
     * @param previousPlanJson  上一次针对同岗位生成的复习计划 JSON（来自 interview_records 表），无历史则为 null
     * @param userId            候选人 ID（B3 工具绑定题库/历史画像时需要按用户隔离）
     */
    public ReviewPlan plan(EvaluationReport report, String previousPlanJson, String userId) {
        log.info("[ReviewPlanner] 开始生成复习计划");

        String reportJson;
        try {
            reportJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            reportJson = report.toString();
        }
        StringBuilder userMsgBuilder = new StringBuilder();
        userMsgBuilder.append("## 面试评估报告\n").append(reportJson);
        if (previousPlanJson != null && !previousPlanJson.isEmpty()) {
            userMsgBuilder.append("\n\n## 上一次复习计划（同岗位，仅供参考，避免重复推荐相同资源）\n")
                    .append(previousPlanJson);
        }
        userMsgBuilder.append("\n").append(OUTPUT_FORMAT);
        String userMsg = userMsgBuilder.toString();

        return AgentUtils.callWithRetry("复习计划生成", AgentUtils.DEFAULT_MAX_ATTEMPTS, () -> {
            String content = generateWithReactAgent(userMsg, userId);
            if (content == null) {
                // 降级：ReactAgent 不可用时退回单轮生成（无工具）
                content = chatModel.call(new Prompt(List.of(
                        new SystemMessage(REVIEW_PLANNER_INSTRUCTION),
                        new UserMessage(userMsg)
                ))).getResult().getOutput().getText();
            }
            return parsePlan(content, report.getSessionId());
        });
    }

    /**
     * 解析 LLM 输出为 ReviewPlan，并对缺失的列表字段做空集合规范化，避免后续 NPE。
     * 包级可见，便于单元测试覆盖「LLM 漏字段」场景。
     */
    ReviewPlan parsePlan(String content, String sessionId) {
        String json = AgentUtils.extractJSON(content);
        try {
            ReviewPlan plan = objectMapper.readValue(json, ReviewPlan.class);
            plan.setSessionId(sessionId);
            plan.setCreatedAt(LocalDateTime.now());
            // 容错：LLM 输出可能缺字段导致列表为 null，规范化为空集合，避免后续 NPE
            if (plan.getWeakAreas() == null) plan.setWeakAreas(new ArrayList<>());
            if (plan.getStudyPlan() == null) plan.setStudyPlan(new ArrayList<>());
            if (plan.getResources() == null) plan.setResources(new ArrayList<>());
            log.info("[ReviewPlanner] 复习计划生成完成，{} 个薄弱领域", plan.getWeakAreas().size());
            return plan;
        } catch (Exception e) {
            throw new RuntimeException("复习计划解析失败: " + e.getMessage(), e);
        }
    }

    /** 与解析不同：仅用于 B2 反思循环内部的"预检查"，解析失败时静默返回 null，不抛异常、不影响主流程 */
    private ReviewPlan tryParseSilently(String content) {
        try {
            String json = AgentUtils.extractJSON(content);
            ReviewPlan plan = objectMapper.readValue(json, ReviewPlan.class);
            if (plan.getWeakAreas() == null) plan.setWeakAreas(new ArrayList<>());
            if (plan.getStudyPlan() == null) plan.setStudyPlan(new ArrayList<>());
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    /** B2：找出 weak_areas 里 priority=high、但 study_plan 里找不到对应学习条目的主题 */
    private List<String> findUncoveredHighPriorityAreas(ReviewPlan draft) {
        List<String> missing = new ArrayList<>();
        if (draft.getWeakAreas() == null) {
            return missing;
        }
        List<ReviewPlan.StudyItem> studyPlan = draft.getStudyPlan() != null ? draft.getStudyPlan() : List.of();
        for (ReviewPlan.WeakArea wa : draft.getWeakAreas()) {
            if (!"high".equalsIgnoreCase(wa.getPriority())) {
                continue;
            }
            if (!isTopicCovered(wa.getTopic(), studyPlan)) {
                missing.add(wa.getTopic());
            }
        }
        return missing;
    }

    /** 模糊匹配：study_plan 的 topic/objective 里能找到薄弱点关键词（互相包含即算覆盖） */
    private boolean isTopicCovered(String weakTopic, List<ReviewPlan.StudyItem> studyPlan) {
        String norm = normalizeTopic(weakTopic);
        if (norm.isEmpty()) {
            return true;
        }
        for (ReviewPlan.StudyItem item : studyPlan) {
            String itemTopic = normalizeTopic(item.getTopic());
            if (!itemTopic.isEmpty() && (itemTopic.contains(norm) || norm.contains(itemTopic))) {
                return true;
            }
            if (item.getObjective() != null && normalizeTopic(item.getObjective()).contains(norm)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTopic(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[\\s\\p{Punct}]+", "");
    }

    private String buildMissingAreaObservation(List<String> missingTopics) {
        return "自检发现：以下高优先级薄弱点在你刚生成的 study_plan 里没有找到对应的学习条目：["
                + String.join("、", missingTopics)
                + "]。请补充这些薄弱点对应的学习条目（可以调用工具检索真实资源/题目），"
                + "并重新输出【完整】的 JSON 对象（包含之前已经生成的、没问题的内容，不要只输出补充部分）。";
    }

    /** B3：按当前可用的工具组装工具列表（题库/GitHub 需要有 RAG 组件支撑才生效） */
    private List<ToolCallback> buildTools(String userId) {
        List<ToolCallback> tools = new ArrayList<>();
        if (gitHubTool != null) {
            tools.add(gitHubTool.asToolCallback());
        }
        if (questionBankTool != null && questionBankTool.isAvailable()) {
            tools.add(questionBankTool.asReviewRecommendTool(userId));
        }
        if (webScraperTool != null) {
            tools.add(webScraperTool.asToolCallback());
        }
        if (candidateProfileTool != null) {
            tools.add(candidateProfileTool.asToolCallback(userId));
        }
        return tools;
    }

    /**
     * 用 ReactAgent（带多工具，由模型自主调用）生成复习计划文本。失败返回 null 以便降级。
     * <p>
     * B2 反思循环：拿到第一版结果后，用纯规则代码检查"高优先级薄弱点是否都被 study_plan 覆盖"，
     * 漏了就把缺口作为新的 UserMessage 追加进对话历史，再调用同一个 agent 补充——
     * 对话历史（{@code history}）持续增长，这一步才是"模型能看到自己上一轮说了什么"的真正体现，
     * 区别于 JDAnalyzer 那种"每次都是全新 Prompt、靠字符串拼接模拟上下文"的做法。
     */
    private String generateWithReactAgent(String userMsg, String userId) {
        try {
            List<ToolCallback> tools = buildTools(userId);

            ReactAgent agent = ReactAgent.builder()
                    .name("review_planner")
                    .model(chatModel)
                    .instruction(REVIEW_PLANNER_INSTRUCTION)
                    .tools(tools)
                    .build();

            AssistantMessage msg = agent.call(userMsg);
            if (msg == null) {
                return null;
            }
            String content = msg.getText();

            List<Message> history = new ArrayList<>();
            history.add(new UserMessage(userMsg));
            history.add(msg);

            for (int round = 1; round <= MAX_REFLECT_ROUNDS; round++) {
                ReviewPlan draft = tryParseSilently(content);
                if (draft == null) {
                    break; // 解析都失败，交给外层 callWithRetry 整体重试，反思环节不参与
                }
                List<String> missing = findUncoveredHighPriorityAreas(draft);
                if (missing.isEmpty()) {
                    break;
                }

                log.info("[ReviewPlanner] 第{}轮反思：发现{}个高优先级薄弱点未被复习计划覆盖：{}",
                        round, missing.size(), missing);
                history.add(new UserMessage(buildMissingAreaObservation(missing)));

                AssistantMessage retry = agent.call(history);
                if (retry == null) {
                    break;
                }
                content = retry.getText();
                history.add(retry);
            }

            return content;
        } catch (Exception e) {
            log.warn("[ReviewPlanner] ReactAgent 执行失败，降级为单轮生成: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 格式化复习计划为 Markdown（与 Go 版本 FormatReviewPlan 对齐）
     */
    public static String formatReviewPlan(ReviewPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 个性化复习计划\n\n");

        if (plan.getWeakAreas() != null && !plan.getWeakAreas().isEmpty()) {
            sb.append("## 薄弱领域\n\n");
            sb.append("| 领域 | 得分 | 优先级 |\n|------|------|--------|\n");
            plan.getWeakAreas().forEach(wa ->
                    sb.append(String.format("| %s | %.0f | %s |\n", wa.getTopic(), wa.getScore(), wa.getPriority())));
            sb.append("\n");
        }

        if (plan.getStudyPlan() != null && !plan.getStudyPlan().isEmpty()) {
            sb.append("## 学习计划\n\n");
            for (ReviewPlan.StudyItem item : plan.getStudyPlan()) {
                sb.append(String.format("### %s\n\n", item.getTopic()));
                sb.append(String.format("**目标**：%s\n\n", item.getObjective()));
                sb.append(String.format("**预估时间**：%s\n\n", item.getTimeEstimate()));
                if (item.getActions() != null) {
                    sb.append("**行动步骤**：\n");
                    item.getActions().forEach(a -> sb.append("- ").append(a).append("\n"));
                    sb.append("\n");
                }
            }
        }

        if (plan.getResources() != null && !plan.getResources().isEmpty()) {
            sb.append("## 推荐资源\n\n");
            for (ReviewPlan.Resource res : plan.getResources()) {
                sb.append(String.format("- **%s**（%s）", res.getTitle(), res.getType()));
                if (res.getUrl() != null && !res.getUrl().isEmpty()) {
                    sb.append(String.format("：[链接](%s)", res.getUrl()));
                }
                if (res.getDesc() != null && !res.getDesc().isEmpty()) {
                    sb.append(" — ").append(res.getDesc());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
