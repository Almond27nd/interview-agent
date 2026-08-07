/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.agent.*;
import com.interview.agent.memory.*;
import com.interview.agent.model.*;
import com.interview.agent.rag.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 面试流程编排器 —— 用 Spring AI Alibaba Graph（StateGraph）把 6 阶段编排成有向图。
 * <p>
 * 图结构：
 * <pre>
 *   START → jd_analysis → resume_match → question_plan → interview
 *                                                            │
 *                                       (用户未作答即终止) ──┴── END
 *                                                            │
 *                                          weak_review → evaluation → review_plan → END
 * </pre>
 * 说明：graph 在执行节点前会对 OverAllState 做 Jackson 深拷贝快照，因此<strong>不能</strong>把
 * 回调（含会阻塞的 getUserAnswer）和业务对象塞进 state。这里让 graph 的 state 保持为空、只负责
 * 编排节点的执行顺序与条件分支；面试上下文（输入、各阶段产物、回调）放在一个 per-interview 的
 * {@link Ctx} 持有者里，由各节点闭包捕获共享。对外行为、回调时序、前端消息协议与顺序编排版本一致。
 */
@Slf4j
@Component
public class Orchestrator {

    private final JDAnalyzer jdAnalyzer;
    private final ResumeMatcher resumeMatcher;
    private final QuestionPlanner questionPlanner;
    private final Interviewer interviewer;
    private final Evaluator evaluator;
    private final ReviewPlanner reviewPlanner;
    private final LongTermMemory longTermMem;
    private final MemoryRecallService memoryRecallService;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final Reranker reranker;
    private final MySQLStore mysqlStore;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * 候选人画像更新专用线程池。画像文本只作为 {@link Interviewer#askQuestion} 里的软上下文
     * （影响提问措辞语气），不参与选题/难度调节（选题在 {@link StageScheduler} 里完全由分数驱动，
     * 见 record(score)），因此把"调 LLM 重新生成整段画像"这一步改为异步执行，不阻塞下一题的提问。
     * 单独隔离出一个小线程池，避免和 node_async 占用的 ForkJoinPool.commonPool 相互抢占。
     */
    private final ExecutorService profileUpdateExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "profile-update-worker");
        t.setDaemon(true);
        return t;
    });

    public Orchestrator(JDAnalyzer jdAnalyzer, ResumeMatcher resumeMatcher,
                        QuestionPlanner questionPlanner, Interviewer interviewer,
                        Evaluator evaluator, ReviewPlanner reviewPlanner,
                        LongTermMemory longTermMem, MemoryRecallService memoryRecallService,
                        MilvusStore milvusStore, BM25Manager bm25Manager,
                        Reranker reranker, MySQLStore mysqlStore,
                        ObservationRegistry observationRegistry,
                        MeterRegistry meterRegistry) {
        this.jdAnalyzer = jdAnalyzer;
        this.resumeMatcher = resumeMatcher;
        this.questionPlanner = questionPlanner;
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.reviewPlanner = reviewPlanner;
        this.longTermMem = longTermMem;
        this.memoryRecallService = memoryRecallService;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
        this.reranker = reranker;
        this.mysqlStore = mysqlStore;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行完整面试流程：构建并编译一张 StateGraph（节点闭包捕获本次面试上下文），驱动它跑完。
     */
    public Session runInterview(String jdText, String resumeText, String userID,
                                InterviewCallbacks cb) throws Exception {
        Ctx c = new Ctx(jdText, resumeText, userID, cb);
        c.session = new Session();
        c.session.setId(UUID.randomUUID().toString());
        c.session.setUserId(userID);
        c.session.setCreatedAt(LocalDateTime.now());
        c.session.setStatus(Session.STATUS_INIT);

        // state 留空（只放一个占位 key，不放业务对象，避免深拷贝序列化）
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            keys.put("_", new ReplaceStrategy());
            return keys;
        };

        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("jd_analysis", node_async(s -> { observeStage("jd_analysis", c.session, () -> jdAnalysis(c)); return Map.of(); }))
                .addNode("resume_match", node_async(s -> { observeStage("resume_match", c.session, () -> resumeMatch(c)); return Map.of(); }))
                .addNode("question_plan", node_async(s -> { observeStage("question_plan", c.session, () -> questionPlan(c)); return Map.of(); }))
                .addNode("interview", node_async(s -> { observeStage("interview", c.session, () -> interview(c)); return Map.of(); }))
                .addNode("weak_review", node_async(s -> { observeStage("weak_review", c.session, () -> weakReview(c)); return Map.of(); }))
                .addNode("evaluation", node_async(s -> { observeStage("evaluation", c.session, () -> evaluation(c)); return Map.of(); }))
                .addNode("review_plan", node_async(s -> { observeStage("review_plan", c.session, () -> reviewPlan(c)); return Map.of(); }))
                .addEdge(START, "jd_analysis")
                // jd_analysis 内部可能因用户在澄清环节主动退出而提前终止（见 jdAnalysis/afterJDAnalysis），
                // 此时直接结束整条流程，不再进入后续阶段（与 interview 节点的提前终止分支同构）。
                .addConditionalEdges("jd_analysis", edge_async(s -> afterJDAnalysis(c)),
                        Map.of("end", END, "continue", "resume_match"))
                // resume_match 同样可能因用户在澄清环节主动退出而提前终止，与 jd_analysis 同构。
                .addConditionalEdges("resume_match", edge_async(s -> afterResumeMatch(c)),
                        Map.of("end", END, "continue", "question_plan"))
                .addEdge("question_plan", "interview")
                .addConditionalEdges("interview", edge_async(s -> afterInterview(c)),
                        Map.of("end", END, "continue", "weak_review"))
                .addEdge("weak_review", "evaluation")
                .addEdge("evaluation", "review_plan")
                .addEdge("review_plan", END);

        CompiledGraph compiledGraph = graph.compile();
        log.info("[Orchestrator] 面试流程 StateGraph 已编译，开始执行");
        compiledGraph.invoke(new HashMap<>());
        log.info("[Orchestrator] 面试流程 StateGraph 执行完成");

        return c.session;
    }

    /**
     * 用手动 Observation API 包裹节点方法调用，产生一个阶段 span。
     * <p>
     * 不用 {@code @Observed} 注解的原因：节点方法是 private 且被 StateGraph 的 node_async lambda
     * 间接调用，Spring AOP 代理无法拦截。手动 Observation 不依赖代理，在任何调用方式下都生效。
     * <p>
     * 阶段 span 作为父 span，自动包裹其内部所有 chatModel.call() 产生的 LLM 调用子 span
     *（Observation 通过 ThreadLocal 传播，同一线程内的子调用自动嵌套）。
     *
     * @param stageName 阶段名（如 "jd_analysis"），作为 span 名称的一部分
     * @param session   当前面试会话，用于在 span 上标注 sessionId
     * @param action    节点方法
     */
    private void observeStage(String stageName, Session session, Runnable action) {
        Observation observation = Observation.createNotStarted("interview.stage." + stageName, observationRegistry)
                .lowCardinalityKeyValue("interview.stage", stageName);
        if (session != null && session.getId() != null) {
            observation.lowCardinalityKeyValue("session.id", session.getId());
        }
        observation.observe(action);
    }

    // ============================================================
    // 各阶段节点（读写 Ctx，调回调推送前端）
    // ============================================================

    /**
     * jd_analysis / resume_match 语义自评不通过时，最多向用户追问几轮；
     * 超过仍不足则带着现有信息继续，不无限等待
     */
    private static final int MAX_CLARIFY_ROUNDS = 2;

    /**
     * 阶段 1：JD 分析。
     * <p>
     * 与规则层的长度校验（WebSocketHandler.validateInterviewInput）不同，这里是 LLM 在做完
     * 分析后对"信息是否足够支撑后续分析"的语义自评（{@link JDAnalysis#isSufficient()}）。
     * 不够时不直接报错拒绝整条流程，而是把当前阶段暂停在原地，通过
     * {@link InterviewCallbacks#requestClarification} 阻塞等待用户补充说明，拿到补充内容后
     * 累加进 JD 原文重新分析，最多循环 {@link #MAX_CLARIFY_ROUNDS} 轮；轮次耗尽仍不足则
     * 带着现有信息继续往下走（报告里已通过 jd_analysis_partial 提示局限性），避免无限等待。
     * 用户若在澄清过程中主动退出（/quit），则整条面试流程随之终止（见 afterJDAnalysis）。
     */
    private void jdAnalysis(Ctx c) {
        c.cb.onStageChange("jd_analysis", "正在分析岗位 JD...");

        String jdText = c.jdText;
        JDAnalysis analysis = jdAnalyzer.analyze(jdText);

        int round = 0;
        while (!analysis.isSufficient() && round < MAX_CLARIFY_ROUNDS) {
            round++;
            String question = analysis.getClarifyQuestion() != null && !analysis.getClarifyQuestion().isEmpty()
                    ? analysis.getClarifyQuestion()
                    : "JD 信息不足以支撑后续分析，请补充更多岗位职责/技术要求信息。";

            String supplement;
            try {
                supplement = c.cb.requestClarification("jd_analysis", question);
            } catch (UserQuitException e) {
                c.userTerminated = true;
                c.session.setStatus(Session.STATUS_TERMINATED);
                c.cb.onStageChange("terminated", "用户在补充 JD 信息环节主动终止面试。");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                c.userTerminated = true;
                c.session.setStatus(Session.STATUS_TERMINATED);
                return;
            }

            // 用户点击"以当前信息继续"按钮时，前端不走自由文本，直接由 WebSocketHandler 塞入
            // 这个约定好的哨兵值——不再把它当补充内容拼回 JD 重新分析（那样大概率仍会被判定
            // sufficient=false，白白多问一轮），而是提前结束澄清循环，走下方既有的
            // "轮次耗尽仍不足"降级路径，尊重用户"确实没有更多信息、就这么继续"的显式选择。
            if (HumanGate.CONTINUE_WITH_CURRENT_INFO.equals(supplement)) {
                c.cb.onStageChange("jd_analysis",
                        "用户选择以当前信息继续，将基于现有信息继续分析。");
                break;
            }

            // 累加而非替换：保留原始 JD 全文，补充内容作为追加信息，避免信息丢失
            jdText = jdText + "\n\n【用户补充说明】" + supplement;
            c.cb.onStageChange("jd_analysis",
                    String.format("正在结合补充信息重新分析（第 %d/%d 轮）...", round, MAX_CLARIFY_ROUNDS));
            analysis = jdAnalyzer.analyze(jdText);
        }

        if (!analysis.isSufficient()) {
            c.cb.onStageChange("jd_analysis_partial",
                    "JD 信息经多轮补充仍不完整，将基于现有信息尽力分析，后续结果可能不够精准。");
        }

        c.jdAnalysis = analysis;
        c.session.setJdAnalysis(c.jdAnalysis);
        c.session.setStatus(Session.STATUS_JD_ANALYZED);

        c.cb.onStageChange("jd_analysis_done",
                String.format("JD 分析完成：%s - %s", c.jdAnalysis.getPosition(), c.jdAnalysis.getExperienceLevel()));
    }

    /** jd_analysis 之后的分支：用户在澄清环节主动退出 → 直接结束；否则继续走完整流程。 */
    private String afterJDAnalysis(Ctx c) {
        return c.userTerminated ? "end" : "continue";
    }

    /**
     * 阶段 2：简历匹配。
     * <p>
     * 与 jd_analysis 完全同构：{@code WebSocketHandler.validateInterviewInput} 只做规则层的
     * 长度校验（挡掉明显过短的输入），这里是 LLM 在做完匹配后对"简历内容是否有效可用"的语义自评
     * （{@link ResumeMatchResult#isSufficient()}）——覆盖长度达标但内容本身无效的情形，例如
     * PDF/扫描件解析出乱码、简历只有姓名联系方式没有任何工作经历/项目/技能等。不够时不直接报错拒绝
     * 整条流程，而是暂停在原地通过 {@link InterviewCallbacks#requestClarification} 阻塞等待用户
     * 补充或修正，拿到补充内容后累加进简历原文重新匹配，最多循环 {@link #MAX_CLARIFY_ROUNDS} 轮；
     * 轮次耗尽仍不足则带着现有信息继续往下走（报告里已通过 resume_match_partial 提示局限性），
     * 避免无限等待。用户若在澄清过程中主动退出，则整条面试流程随之终止（见 afterResumeMatch）。
     */
    private void resumeMatch(Ctx c) {
        c.cb.onStageChange("resume_match", "正在分析简历匹配度...");

        String resumeText = c.resumeText;
        c.resume = new Resume();
        c.resume.setRawText(resumeText);
        ResumeMatchResult result = resumeMatcher.match(c.jdAnalysis, c.resume);

        int round = 0;
        while (!result.isSufficient() && round < MAX_CLARIFY_ROUNDS) {
            round++;
            String question = result.getClarifyQuestion() != null && !result.getClarifyQuestion().isEmpty()
                    ? result.getClarifyQuestion()
                    : "简历信息不足以支撑匹配分析，请补充更完整的简历内容（工作经历/项目/技能等）。";

            String supplement;
            try {
                supplement = c.cb.requestClarification("resume_match", question);
            } catch (UserQuitException e) {
                c.userTerminated = true;
                c.session.setStatus(Session.STATUS_TERMINATED);
                c.cb.onStageChange("terminated", "用户在补充简历信息环节主动终止面试。");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                c.userTerminated = true;
                c.session.setStatus(Session.STATUS_TERMINATED);
                return;
            }

            // 用户点击"以当前信息继续"按钮：与 jd_analysis 一致，直接结束澄清循环，
            // 不把哨兵值当补充内容拼回简历重新匹配。
            if (HumanGate.CONTINUE_WITH_CURRENT_INFO.equals(supplement)) {
                c.cb.onStageChange("resume_match",
                        "用户选择以当前信息继续，将基于现有简历继续分析。");
                break;
            }

            // 累加而非替换：保留原始简历全文，补充内容作为追加信息，避免信息丢失
            resumeText = resumeText + "\n\n【用户补充说明】" + supplement;
            c.resume.setRawText(resumeText);
            c.cb.onStageChange("resume_match",
                    String.format("正在结合补充信息重新匹配（第 %d/%d 轮）...", round, MAX_CLARIFY_ROUNDS));
            result = resumeMatcher.match(c.jdAnalysis, c.resume);
        }

        if (!result.isSufficient()) {
            c.cb.onStageChange("resume_match_partial",
                    "简历信息经多轮补充仍不完整，将基于现有信息尽力匹配，后续结果可能不够精准。");
        }

        c.matchResult = result;
        c.session.setMatchResult(c.matchResult);
        c.session.setStatus(Session.STATUS_RESUME_MATCHED);

        c.cb.onStageChange("resume_match_done",
                String.format("简历匹配完成，综合匹配度：%.0f%%", c.matchResult.getOverallScore()));
    }

    /** resume_match 之后的分支：用户在澄清环节主动退出 → 直接结束；否则继续走完整流程。 */
    private String afterResumeMatch(Ctx c) {
        return c.userTerminated ? "end" : "continue";
    }

    /** 阶段 2.5 + 3：读取历史薄弱点 + 出题规划（Phase1 方向 + Phase2 检索/组装） */
    private void questionPlan(Ctx c) {
        String userID = c.userID;

        // ===== 阶段 2.5：混合召回历史薄弱点（M3）=====
        // 原实现用双向字符串 contains 判断薄弱点与当前 JD 是否相关，对同义词/上下位概念无能为力
        // （「分布式事务」与 JD 的「Seata」「两阶段提交」字符串互不包含，会被误判为不相关而降权）。
        // 现在改为三路混合召回后 RRF 融合：语义（embedding）补同义、词法保精确、
        // 记忆固有优先级（掌握度/顽固/复发）体现「哪个更该考」。
        // 跨岗位共性薄弱点仍不硬性过滤，只是排在强相关之后供 LLM 参考（保持原设计意图）。
        //
        // 这里取的是【候选池】（getWeakPointCandidates，上限 30）而非最终 Top10：
        // getWeakPoints 的签名里没有任何 JD 信息，在那一层截断到 10 条意味着
        // 「截断发生在唯一不知道 JD 的层，而判断 JD 相关性的层反而没有筛选空间」——
        // 跨岗位场景下与本次 JD 强相关的条目可能排在第 11 名之后，在召回开始前就已丢失。
        // 放宽到 30 后，最终收敛由下面的三路 RRF（30 进 10）完成。
        String weakPointsContext = "";
        List<UserProfile.WeakPoint> weakPoints = longTermMem.getWeakPointCandidates(userID);
        if (weakPoints != null && !weakPoints.isEmpty()) {
            List<String> jdSkills = collectJDSkills(c.jdAnalysis);

            MemoryRecallService.RecallResult recalled =
                    memoryRecallService.recall(weakPoints, jdSkills, c.jdAnalysis.getPosition());

            List<String> wpLines = new ArrayList<>();
            for (UserProfile.WeakPoint wp : recalled.getRelevant()) {
                wpLines.add("- [强相关] " + describeWeakPoint(wp));
            }
            for (UserProfile.WeakPoint wp : recalled.getOthers()) {
                wpLines.add("- [其他岗位历史薄弱点，供参考] " + describeWeakPoint(wp));
            }

            if (recalled.getRelevant().isEmpty() && !recalled.getOthers().isEmpty()) {
                meterRegistry.counter("interview.weakpoint.relevant.zero").increment();
            }

            if (!wpLines.isEmpty()) {
                weakPointsContext = String.join("\n", wpLines);
                c.cb.onStageChange("memory_loaded",
                        String.format("已加载 %d 个历史薄弱点（%d 个与当前岗位强相关），将针对性出题",
                                wpLines.size(), recalled.getRelevant().size()));
                log.info("[Plan] 记忆召回策略={}{}", recalled.getStrategy(),
                        recalled.isDegraded() ? "（语义通道不可用，已降级）" : "");
            }
        }

        // ===== 阶段 2.5b：读取同岗位历史面试趋势（跨会话，感知候选人是否多次面试过该岗位） =====
        String interviewHistoryContext = "";
        if (mysqlStore != null) {
            try {
                List<UserProfile.InterviewRecord> pastRecords =
                        mysqlStore.getRecentInterviewRecords(userID, c.jdAnalysis.getPosition(), 3);
                interviewHistoryContext = buildInterviewHistoryContext(pastRecords);
                if (!interviewHistoryContext.isEmpty()) {
                    c.cb.onStageChange("memory_loaded",
                            String.format("检测到候选人已针对该岗位面试过 %d 次，将结合历史趋势调整出题策略", pastRecords.size()));
                }
            } catch (Exception e) {
                log.warn("[Orchestrator] 查询历史面试趋势失败（不影响主流程）: {}", e.getMessage());
            }
        }

        // ===== 阶段 3 Phase 1：规划出题方向 =====
        c.cb.onStageChange("question_plan", "正在规划出题方向...");

        QuestionDirectionPlan dirPlan = questionPlanner.planDirections(
                c.jdAnalysis, c.matchResult, weakPointsContext, interviewHistoryContext);
        log.info("[Plan] Phase 1 完成，规划了 {} 个出题方向", dirPlan.getDirections().size());

        // ===== 阶段 3 Phase 2：按方向检索题库 + 组装题目 =====
        // B1（Agentic RAG，按难度分组版）：basic 类方向不再"每个方向 new 一个 agent、串行调用"，
        // 而是先按 difficulty 分组（固定 easy/medium/hard 三档），同一档的方向一次性交给一个
        // ReactAgent 会话批量处理——agent 实例数量恒定为 3，不随方向数量增长；模型在一轮会话里
        // 对组内每个方向依次决定检索关键词、要不要重试、最终用原题还是自己出题。
        // 整组调用失败/题库不可用时该组整体降级；调用成功但漏了组内某几个方向时，只把漏掉的
        // 方向单独降级，命中的照常使用，不用"一颗老鼠屎坏一锅粥"。
        //
        // B4（Multi-Agent 审题回环）：assembleBasicQuestionsWithAgent 内部已升级为
        // "出题 Agent → 规则预检 → 审题 Agent →（有打回则回炉重出）→ 定稿"的一张小 StateGraph，
        // 对本处的调用契约完全不变（仍返回 Map<组内下标, PlannedQuestion>），因此这里无需改动：
        // 拿到的题目已经是经过独立审题 Agent 质检、必要时重出过的版本。
        boolean hasRAG = milvusStore != null || bm25Manager != null;
        List<PlannedQuestion> matchedQuestions = new ArrayList<>();
        List<QuestionDirection> unmatchedDirs = new ArrayList<>();
        int matchedCount = 0;
        int agenticCount = 0;
        int fallbackCount = 0;

        if (hasRAG) {
            c.cb.onStageChange("rag_retrieval", "正在按难度分组、自主检索题库、批量组装并审核题目...");

            List<QuestionDirection> allDirs = dirPlan.getDirections();

            // 按 type + difficulty 分组：basic 类走带检索工具的审题子图，
            // experience/design 类走无工具的审题子图（单轮 LLM 出题 + 审题回环）。
            // key 格式: "type:difficulty"，同一组内共享一个 Agent 会话。
            Map<String, List<Integer>> agenticIndexByGroup = new LinkedHashMap<>();
            for (int i = 0; i < allDirs.size(); i++) {
                QuestionDirection dir = allDirs.get(i);
                String groupKey = dir.getType() + ":" + dir.getDifficulty();
                agenticIndexByGroup.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(i);
            }

            for (Map.Entry<String, List<Integer>> entry : agenticIndexByGroup.entrySet()) {
                String groupKey = entry.getKey();
                String[] parts = groupKey.split(":", 2);
                String type = parts[0];
                String difficulty = parts.length > 1 ? parts[1] : "medium";
                List<Integer> globalIndices = entry.getValue();
                List<QuestionDirection> groupDirs = new ArrayList<>();
                for (int gi : globalIndices) {
                    groupDirs.add(allDirs.get(gi));
                }

                // 一档一次 agent 会话，覆盖该档下全部方向
                // basic 类走带检索工具的 ReactAgent；experience/design 类走无工具单轮 LLM + 审题回环
                Map<Integer, PlannedQuestion> agentResults = "basic".equals(type)
                        ? questionPlanner.assembleBasicQuestionsWithAgent(groupDirs, userID)
                        : questionPlanner.assembleExpDesignWithReview(groupDirs);

                for (int localIdx = 0; localIdx < groupDirs.size(); localIdx++) {
                    int globalIdx = globalIndices.get(localIdx);
                    QuestionDirection dir = groupDirs.get(localIdx);
                    PlannedQuestion agentPQ = agentResults.get(localIdx);
                    if (agentPQ != null) {
                        agentPQ.setId("q" + (globalIdx + 1));
                        matchedQuestions.add(agentPQ);
                        agenticCount++;
                        if (!"llm".equals(agentPQ.getSource())) {
                            matchedCount++;
                        }
                        log.info("[RAG] 方向 {}（难度{}）由分组 Agent 自主决策完成，source={}",
                                globalIdx + 1, difficulty, agentPQ.getSource());
                        continue;
                    }

                    // ===== 降级：整组失败，或该组内单个方向未被覆盖时，仅对这一个方向
                    // 退回原有的手写 pipeline（检索 → Rerank 取 top1 → 命中即用原题 / 否则转 LLM 兜底出题）=====
                    fallbackCount++;
                    String query = dir.getSearchQuery() != null && !dir.getSearchQuery().isEmpty()
                            ? dir.getSearchQuery() : dir.getTopic();
                    log.info("[RAG] 方向 {} 降级为传统 pipeline 检索: query={}", globalIdx + 1, query);

                    List<RagDocument> docs = new ArrayList<>();
                    Set<String> seen = new HashSet<>();

                    if (milvusStore != null) {
                        try {
                            List<RagDocument> milvusDocs = milvusStore.retrieveByUser(userID, query, 10);
                            for (RagDocument doc : milvusDocs) {
                                if (!seen.contains(doc.getId())) {
                                    seen.add(doc.getId());
                                    docs.add(doc);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[RAG] Milvus 检索失败（方向{}）: {}", globalIdx + 1, e.getMessage());
                        }
                    }

                    if (bm25Manager != null) {
                        try {
                            List<RagDocument> bm25Docs = bm25Manager.retrieve(userID, query);
                            for (RagDocument doc : bm25Docs) {
                                if (!seen.contains(doc.getId())) {
                                    seen.add(doc.getId());
                                    docs.add(doc);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[RAG] BM25 检索失败（方向{}）: {}", globalIdx + 1, e.getMessage());
                        }
                    }

                    if (!docs.isEmpty()) {
                        // Rerank 取 top 1
                        List<RagDocument> reranked = reranker.rerank(query, docs);
                        if (reranked == null || reranked.isEmpty()) reranked = docs;
                        RagDocument topDoc = reranked.get(0);
                        log.info("[RAG] 方向 {} 匹配到题库原题 [{}]", globalIdx + 1, topDoc.getId());

                        String questionContent = topDoc.getContent();
                        String reference = "";
                        int refIdx = questionContent.indexOf("\n参考答案：");
                        if (refIdx >= 0) {
                            reference = questionContent.substring(refIdx + "\n参考答案：".length()).trim();
                            questionContent = questionContent.substring(0, refIdx).trim();
                        }

                        PlannedQuestion pq = new PlannedQuestion();
                        pq.setId("q" + (globalIdx + 1));
                        pq.setContent(questionContent);
                        pq.setType(dir.getType());
                        pq.setDifficulty(dir.getDifficulty());
                        pq.setSkills(dir.getSkills());
                        pq.setFollowUps(List.of());
                        pq.setReference(reference);
                        pq.setSource(topDoc.getId());
                        matchedQuestions.add(pq);
                        matchedCount++;
                    } else {
                        log.info("[RAG] 方向 {} 无匹配题目，交给 LLM", globalIdx + 1);
                        unmatchedDirs.add(dir);
                    }
                }
            }

            c.cb.onStageChange("rag_retrieval_done",
                    String.format("题库检索完成，%d 道由分组 Agent 自主决策（其中%d道降级为传统流程），%d 道由 LLM 兜底出题",
                            agenticCount, fallbackCount, unmatchedDirs.size()));
        } else {
            unmatchedDirs.addAll(dirPlan.getDirections());
        }

        // 未匹配的方向交给 LLM 出题
        List<PlannedQuestion> llmQuestions = new ArrayList<>();
        if (!unmatchedDirs.isEmpty()) {
            c.cb.onStageChange("question_assemble",
                    String.format("正在为 %d 个方向生成面试题目...", unmatchedDirs.size()));
            QuestionDirectionPlan unmatchedPlan = new QuestionDirectionPlan();
            unmatchedPlan.setDirections(unmatchedDirs);
            List<String> emptyDocs = Collections.nCopies(unmatchedDirs.size(), "");
            QuestionPlan assembled = questionPlanner.assembleQuestions(c.jdAnalysis, c.matchResult, unmatchedPlan, emptyDocs);
            if (assembled != null && assembled.getQuestions() != null) {
                llmQuestions.addAll(assembled.getQuestions());
            }
        }

        // 合并题目
        List<PlannedQuestion> allQuestions = new ArrayList<>(matchedQuestions);
        allQuestions.addAll(llmQuestions);
        for (int i = 0; i < allQuestions.size(); i++) {
            allQuestions.get(i).setId("q" + (i + 1));
        }

        // 统计分布
        int basicCount = 0, expCount = 0, designCount = 0;
        for (PlannedQuestion q : allQuestions) {
            switch (q.getType() != null ? q.getType() : "") {
                case "basic" -> basicCount++;
                case "experience" -> expCount++;
                case "design" -> designCount++;
            }
        }

        QuestionPlan plan = new QuestionPlan();
        plan.setTotalQuestions(allQuestions.size());
        plan.setDistribution(new QuestionPlan.QuestionDistrib(basicCount, expCount, designCount));
        plan.setQuestions(allQuestions);
        c.questionPlan = plan;
        c.session.setQuestionPlan(plan);
        c.session.setStatus(Session.STATUS_PLANNED);

        c.cb.onStageChange("question_plan_done",
                String.format("出题计划完成，共 %d 道题（基础%d/经历%d/设计%d）",
                        plan.getTotalQuestions(), basicCount, expCount, designCount));
    }

    /** 阶段 4：模拟面试（含追问、动态难度调节、薄弱点更新，人在环阻塞交互） */
    private void interview(Ctx c) {
        List<PlannedQuestion> allQuestions = c.questionPlan.getQuestions();

        c.cb.onStageChange("interview", "面试正式开始！");

        // 面试分三个阶段顺序进行：basic → experience → design。
        // 阶段化取题与阶段内难度调节由 StageScheduler 负责（见 StageScheduler.java）：
        // 每阶段从候选池按当前难度自适应抽取固定道数；进入新阶段时难度重置为 medium，不继承上一阶段。
        StageScheduler sched = new StageScheduler(StageScheduler.DEFAULT_STAGES, allQuestions,
                (cur, consecRight, consecWrong) -> questionPlanner.adjustDifficulty(
                        InterviewState.builder()
                                .currentDifficulty(cur)
                                .consecutiveRight(consecRight)
                                .consecutiveWrong(consecWrong)
                                .build()));

        InterviewState state = new InterviewState();
        state.setSessionId(c.session.getId());
        state.setTotalQuestions(sched.totalToAsk());
        state.setCurrentDifficulty("medium");
        state.setQaHistory(new ArrayList<>());
        c.interviewState = state;
        c.session.setInterviewState(state);
        c.session.setStatus(Session.STATUS_INTERVIEWING);

        boolean userTerminated = false;
        int asked = 0;
        while (true) {
            StageScheduler.Picked picked = sched.next();
            if (picked == null) {
                break;
            }
            PlannedQuestion q = picked.question();
            asked++;
            state.setCurrentQuestion(asked);
            state.setCurrentDifficulty(picked.difficulty());
            if (picked.fellBack()) {
                // 目标难度桶已空、被就近取题：说明 Phase 1 该档方向铺得不够（配额缺口的直接后果）。
                // 必须显式记录——否则「难度调节已降到 easy 但实际还在问 hard」这种失效
                // 会被 QuestionPool 的 fallback 完全静默吸收。
                log.warn("[难度调节] 第{}题 type={} 目标难度={} 但该档候选已空，实际取到={}；"
                                + "本档难度调节精度下降（Phase1 配额缺口所致）",
                        asked, q.getType(), picked.targetDifficulty(), picked.actualDifficulty());
                meterRegistry.counter("interview.fallback.count",
                        "target", picked.targetDifficulty(),
                        "actual", picked.actualDifficulty()).increment();
            }
            log.info("[难度调节] 第{}题 type={} 抽取难度={} (上一题后 连对{}/连错{}) 来源={}",
                    asked, q.getType(), picked.difficulty(), sched.getConsecRight(), sched.getConsecWrong(), q.getSource());

            // 面试官提问（流式：LLM 逐 token 生成过程中通过 onQuestionDelta 多次回调，供前端打字机效果）
            final int askedNum = asked;
            String questionText = interviewer.askQuestion(state, q, c.jdAnalysis.getPosition(),
                    delta -> c.cb.onQuestionDelta(askedNum, delta));
            if (q.getSource() != null && !q.getSource().isEmpty() && !"llm".equals(q.getSource())) {
                questionText += String.format("\n\n`[来源: 题库 %s]`", q.getSource());
            } else {
                questionText += "\n\n`[来源: LLM 出题]`";
            }

            c.cb.onQuestion(asked, questionText);

            // 等待用户回答（限时：超过配置的秒数未作答按超时处理，不中断整场面试）
            String answer;
            boolean answerTimedOut = false;
            try {
                answer = c.cb.getUserAnswer();
            } catch (AnswerTimeoutException e) {
                answerTimedOut = true;
                answer = "[超时未作答]";
                c.cb.onTimeout(asked);
            } catch (UserQuitException e) {
                userTerminated = true;
                c.cb.onStageChange("terminated",
                        String.format("用户主动终止面试（已完成 %d/%d 题）", state.getQaHistory().size(), state.getTotalQuestions()));
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                userTerminated = true;
                break;
            }

            // 评分：超时的题目直接判定 0 分，不再调用 LLM 评分（内容为空，评分也没有意义）
            AnswerScore score;
            if (answerTimedOut) {
                score = AnswerScore.builder()
                        .score(0)
                        .feedback("超时未作答")
                        .keyPointsHit(List.of())
                        .keyPointsMissed(List.of())
                        .shouldFollowUp(false)
                        .build();
            } else {
                score = interviewer.scoreAnswer(q, answer);
            }
            c.cb.onScore(score);

            // 更新候选人动态画像：异步执行，不阻塞下一题的提问。
            // 用 c.profileUpdateChain 把同一场面试内的多次更新串成一条链、依次在 profileUpdateExecutor
            // 上执行——既不阻塞主线程往下问下一题，又保证多次更新按发生顺序生效，不会因为并发写
            // 导致"后完成的旧结果覆盖先完成的新结果"这种丢失更新问题。
            final int askedForProfile = asked;
            c.profileUpdateChain = c.profileUpdateChain.thenRunAsync(() -> {
                try {
                    String updatedProfile = interviewer.updateCandidateProfile(
                            state.getCandidateProfile(), askedForProfile, q, score);
                    state.setCandidateProfile(updatedProfile);
                } catch (Exception e) {
                    log.warn("[Profile] 画像更新失败（不影响主流程）: {}", e.getMessage());
                }
            }, profileUpdateExecutor);

            // 记录问答
            QAPair qa = new QAPair();
            qa.setQuestion(q);
            qa.setUserAnswer(answer);
            qa.setScore(score.getScore());
            qa.setFeedback(score.getFeedback());

            // 追问逻辑
            boolean shouldFollowUp = score.isShouldFollowUp()
                    && score.getScore() >= 30 && score.getScore() < 80
                    && score.getKeyPointsMissed() != null && !score.getKeyPointsMissed().isEmpty();

            if (shouldFollowUp) {
                try {
                    String followUpText = interviewer.followUp(state, q, answer,
                            score.getFeedback(), score.getKeyPointsMissed(), c.jdAnalysis.getPosition(),
                            delta -> c.cb.onQuestionDelta(askedNum, delta));
                    c.cb.onQuestion(asked, "[追问] " + followUpText);

                    try {
                        String followUpAnswer = c.cb.getUserAnswer();
                        qa.setFollowUpUsed(true);
                        qa.setUserAnswer(qa.getUserAnswer() + "\n[追问回答] " + followUpAnswer);

                        AnswerScore followUpScore = interviewer.scoreAnswer(q, followUpAnswer);
                        c.cb.onScore(followUpScore);
                    } catch (AnswerTimeoutException e) {
                        c.cb.onTimeout(asked);
                        qa.setFollowUpUsed(true);
                        qa.setUserAnswer(qa.getUserAnswer() + "\n[追问回答] [超时未作答]");
                    } catch (UserQuitException e) {
                        state.getQaHistory().add(qa);
                        userTerminated = true;
                        c.cb.onStageChange("terminated",
                                String.format("用户主动终止面试（已完成 %d/%d 题）",
                                        state.getQaHistory().size(), state.getTotalQuestions()));
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        state.getQaHistory().add(qa);
                        userTerminated = true;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[Interviewer] 追问失败: {}", e.getMessage());
                }
            }

            state.getQaHistory().add(qa);

            // 动态难度调节（阶段内，由 scheduler 维护；同步到 state 供报告/前端展示）
            sched.record(score.getScore());
            state.setConsecutiveRight(sched.getConsecRight());
            state.setConsecutiveWrong(sched.getConsecWrong());

            // 更新薄弱点（best-effort：记忆写入失败不应中断面试主流程）
            //
            // M2 写入门控：超时未作答会被记 0 分，但它反映的是「候选人不在电脑前」（接电话/合盖/断网），
            // 【不代表能力】。而 0 分是最低分，一旦入库就会排在薄弱点最前面，把下一场面试的
            // 出题方向直接带偏——所以这类伪证据必须在入口就拦掉，而不是等到召回时再想办法过滤。
            if (q.getSkills() != null && MemoryWriteGate.acceptAsEvidence(answerTimedOut, answer)) {
                try {
                    for (String skill : q.getSkills()) {
                        // 带上题目难度与场次：hard 题的表现比 easy 题更有说服力，
                        // 掌握度置信度需要按难度加权，避免「蒙对一道简单题就算掌握」。
                        longTermMem.recordEvidence(c.userID, skill, score.getScore(),
                                q.getDifficulty(), c.session.getId());
                    }
                } catch (Exception e) {
                    log.warn("[Orchestrator] 更新薄弱点失败（不影响主流程）: {}", e.getMessage());
                }
            } else if (answerTimedOut) {
                log.info("[Orchestrator] 第 {} 题超时未作答，本题不作为能力证据写入长期记忆", asked);
            }
        }

        c.userTerminated = userTerminated;

        // 终止时设置 session 状态（与顺序版本一致）
        if (userTerminated && state.getQaHistory().isEmpty()) {
            c.session.setStatus(Session.STATUS_TERMINATED);
            c.session.setUpdatedAt(LocalDateTime.now());
            c.cb.onStageChange("completed", "面试未作答即终止，不生成评估报告。");
        } else if (userTerminated) {
            c.session.setStatus(Session.STATUS_TERMINATED);
        }
    }

    /** interview 之后的分支：用户未作答即终止 → 直接结束（不生成报告）；否则进入低分巩固/评估。 */
    private String afterInterview(Ctx c) {
        InterviewState state = c.interviewState;
        if (c.userTerminated && (state.getQaHistory() == null || state.getQaHistory().isEmpty())) {
            return "end";
        }
        return "continue";
    }

    /** 阶段 4.5：低分题目巩固 */
    private void weakReview(Ctx c) {
        InterviewState state = c.interviewState;
        if (state.getQaHistory() != null && !state.getQaHistory().isEmpty()) {
            List<QAPair> weakQAs = state.getQaHistory().stream()
                    .filter(qa -> qa.getScore() < 60)
                    .collect(Collectors.toList());

            if (!weakQAs.isEmpty()) {
                c.cb.onStageChange("review_weak",
                        String.format("正在对 %d 道低分题目进行巩固...", weakQAs.size()));

                for (int idx = 0; idx < weakQAs.size(); idx++) {
                    QAPair qa = weakQAs.get(idx);
                    String reviewContent = buildWeakReviewContent(idx, weakQAs.size(), qa, c.userID);
                    if (reviewContent != null) {
                        c.cb.onQuestion(0, reviewContent);
                    }
                }

                c.cb.onStageChange("review_weak_done", "低分题目巩固完成");
            }
        }
    }

    /** 阶段 5：生成评估报告 */
    private void evaluation(Ctx c) {
        InterviewState state = c.interviewState;

        if (c.userTerminated) {
            c.cb.onStageChange("evaluation",
                    String.format("面试提前终止，正在基于已完成的 %d 道题生成评估报告...",
                            state.getQaHistory().size()));
        } else {
            c.cb.onStageChange("evaluation", "正在生成评估报告...");
            c.session.setStatus(Session.STATUS_EVALUATED);
        }

        c.report = evaluator.evaluate(state, c.jdAnalysis.getPosition(),
                c.resume != null ? c.resume.getName() : null, c.userTerminated);
        c.session.setReport(c.report);

        String reportMD = Evaluator.formatReport(c.report);
        c.cb.onReport(reportMD);
    }

    /** 阶段 6：生成复习计划 + 持久化面试记录 */
    private void reviewPlan(Ctx c) {
        c.cb.onStageChange("review_plan", "正在生成复习计划...");

        // 读取同岗位上一次的完整复习计划（跨会话），供本次规划参考，避免重复推荐
        String previousPlanJson = null;
        if (mysqlStore != null) {
            try {
                previousPlanJson = mysqlStore.getLatestReviewPlanJson(c.userID, c.jdAnalysis.getPosition());
            } catch (Exception e) {
                log.warn("[Orchestrator] 查询上一次复习计划失败（不影响主流程）: {}", e.getMessage());
            }
        }

        c.reviewPlan = reviewPlanner.plan(c.report, previousPlanJson, c.userID);
        c.session.setReviewPlan(c.reviewPlan);
        c.session.setStatus(Session.STATUS_COMPLETED);
        c.session.setUpdatedAt(LocalDateTime.now());

        String planMD = ReviewPlanner.formatReviewPlan(c.reviewPlan);
        c.cb.onReviewPlan(planMD);

        // ===== 按技能聚合本场问答得分，回填技能等级画像（skillLevel）=====
        try {
            Map<String, List<Double>> skillScores = new HashMap<>();
            if (c.interviewState != null && c.interviewState.getQaHistory() != null) {
                for (QAPair qa : c.interviewState.getQaHistory()) {
                    List<String> skills = qa.getQuestion() != null ? qa.getQuestion().getSkills() : null;
                    if (skills == null) continue;
                    for (String skill : skills) {
                        skillScores.computeIfAbsent(skill, k -> new ArrayList<>()).add(qa.getScore());
                    }
                }
            }
            if (!skillScores.isEmpty()) {
                longTermMem.updateSkillLevels(c.userID, skillScores);
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] 回填技能等级失败（不影响主流程）: {}", e.getMessage());
        }

        // ===== 持久化面试记录 =====
        longTermMem.addInterviewRecord(c.userID, UserProfile.InterviewRecord.builder()
                .sessionId(c.session.getId())
                .position(c.jdAnalysis.getPosition())
                .overallScore(c.report.getOverallScore())
                .date(LocalDateTime.now())
                .build());

        if (mysqlStore != null) {
            try {
                String reportJSON = objectMapper.writeValueAsString(c.report);
                String planJSON = objectMapper.writeValueAsString(c.reviewPlan);
                mysqlStore.saveInterviewRecord(c.userID, UserProfile.InterviewRecord.builder()
                        .sessionId(c.session.getId())
                        .position(c.jdAnalysis.getPosition())
                        .overallScore(c.report.getOverallScore())
                        .date(LocalDateTime.now())
                        .build(), reportJSON, planJSON);
            } catch (Exception e) {
                log.warn("[Orchestrator] 保存面试记录到 MySQL 失败: {}", e.getMessage());
            }
        }

        c.cb.onStageChange("completed", "面试流程全部完成！");
    }

    // ============================================================
    // 面试上下文持有者（不进入 graph state，避免被序列化）
    // ============================================================
    private static final class Ctx {
        final String jdText;
        final String resumeText;
        final String userID;
        final InterviewCallbacks cb;

        Session session;
        JDAnalysis jdAnalysis;
        Resume resume;
        ResumeMatchResult matchResult;
        QuestionPlan questionPlan;
        InterviewState interviewState;
        EvaluationReport report;
        ReviewPlan reviewPlan;
        boolean userTerminated;

        /**
         * 画像更新任务链：每次追加一个 thenRunAsync，保证同一场面试内的画像更新严格按提交顺序
         * 串行执行（避免并发写乱序），初始为一个已完成的空 Future，代表"暂无待执行的更新"。
         */
        volatile CompletableFuture<Void> profileUpdateChain = CompletableFuture.completedFuture(null);

        Ctx(String jdText, String resumeText, String userID, InterviewCallbacks cb) {
            this.jdText = jdText;
            this.resumeText = resumeText;
            this.userID = userID;
            this.cb = cb;
        }
    }

    // ============================================================
    // 辅助方法（与顺序版本一致）
    // ============================================================

    /** 构建低分题巩固内容 */
    private String buildWeakReviewContent(int idx, int total, QAPair qa, String userID) {
        PlannedQuestion question = qa.getQuestion();
        String source = question.getSource();

        if (source != null && !source.isEmpty() && !"llm".equals(source)) {
            // 题库出题：优先用参考答案
            String refAnswer = question.getReference();
            if ((refAnswer == null || refAnswer.isEmpty()) && (milvusStore != null || bm25Manager != null)) {
                refAnswer = retrieveReferenceAnswer(userID, question.getContent());
            }
            if (refAnswer != null && !refAnswer.isEmpty()) {
                return String.format("**低分题目巩固 %d/%d**\n\n**题目：** %s\n\n**你的得分：** %.0f\n\n**题库参考答案：**\n%s",
                        idx + 1, total, question.getContent(), qa.getScore(), refAnswer);
            }
        } else if (question.getReference() != null && !question.getReference().isEmpty()) {
            return String.format("**低分题目巩固 %d/%d**\n\n**题目：** %s\n\n**你的得分：** %.0f\n\n**参考答案：**\n%s",
                    idx + 1, total, question.getContent(), qa.getScore(), question.getReference());
        }
        return null;
    }

    private String retrieveReferenceAnswer(String userID, String query) {
        try {
            Set<String> seen = new HashSet<>();
            List<RagDocument> docs = new ArrayList<>();

            if (milvusStore != null) {
                for (RagDocument doc : milvusStore.retrieveByUser(userID, query, 3)) {
                    if (!seen.contains(doc.getId())) {
                        seen.add(doc.getId());
                        docs.add(doc);
                    }
                }
            }
            if (bm25Manager != null) {
                for (RagDocument doc : bm25Manager.retrieve(userID, query)) {
                    if (!seen.contains(doc.getId())) {
                        seen.add(doc.getId());
                        docs.add(doc);
                    }
                }
            }

            if (!docs.isEmpty()) {
                String content = docs.get(0).getContent();
                int refIdx = content.indexOf("\n参考答案：");
                if (refIdx >= 0) {
                    return content.substring(refIdx + "\n参考答案：".length()).trim();
                }
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] 检索参考答案失败: {}", e.getMessage());
        }
        return null;
    }

    /** 收集 JD 中的所有技能关键词（小写） */
    static List<String> collectJDSkills(JDAnalysis jd) {
        List<String> skills = new ArrayList<>();
        if (jd.getRequiredSkills() != null) {
            jd.getRequiredSkills().forEach(s -> skills.add(s.getName().toLowerCase()));
        }
        if (jd.getPreferredSkills() != null) {
            jd.getPreferredSkills().forEach(s -> skills.add(s.getName().toLowerCase()));
        }
        if (jd.getKeyTopics() != null) {
            jd.getKeyTopics().forEach(t -> skills.add(t.toLowerCase()));
        }
        return skills;
    }

    /**
     * 判断薄弱点是否和当前 JD 技能相关。
     *
     * @deprecated 已被 {@link MemoryRecallService} 的混合召回取代（M3）。
     *         这里的双向字符串包含是纯 keyword 匹配，对同义词与上下位概念无能为力——
     *         「分布式事务」与 JD 的「Seata」「两阶段提交」字符串互不包含却是同一考点。
     *         词法匹配能力已并入 {@code MemoryRecallService.lexicalScore}（作为三路之一保留，
     *         因为它对专有名词/缩写比语义检索更可靠），并与语义通道互补。
     *         <p>保留本方法仅为兼容可能的外部调用；新代码请勿使用，避免与召回逻辑双份维护。
     */
    @Deprecated
    static boolean isWeakPointRelevant(String topic, List<String> jdSkills) {
        String topicLower = topic.toLowerCase();
        for (String skill : jdSkills) {
            if (topicLower.contains(skill) || skill.contains(topicLower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一条薄弱点档案渲染成出题 Prompt 里的一行自然语言。
     *
     * <p>相比原来只给「历史得分 / 被考察次数 / 答错次数」，这里额外暴露了证据型记忆算出的
     * <b>掌握度置信度</b>与<b>顽固/复发标记</b>——让 LLM 能区分两种表面相似的情况：
     * <ul>
     *   <li>「考 1 次错 1 次得 55」：偶发薄弱，正常考察即可；</li>
     *   <li>「考 6 次错 5 次得 58、掌握度 0.18、顽固、曾掌握后复发 2 次」：
     *       长期未突破，应重点、换角度反复考察。</li>
     * </ul>
     * 这两种情况在原实现里的排序甚至是反的（只按最新得分排，前者更靠前）。
     */
    static String describeWeakPoint(UserProfile.WeakPoint wp) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s：最近得分 %.0f，被考察 %d 次，答错 %d 次，掌握度 %.2f",
                wp.getTopic(), wp.getScore(), wp.getHitCount(), wp.getWrongCount(), wp.mastery()));
        if (wp.isStubborn()) {
            sb.append("（顽固薄弱点，长期未突破，建议重点且换角度考察）");
        }
        if (wp.getRelapseCount() > 0) {
            sb.append(String.format("（曾判定掌握后又答错 %d 次）", wp.getRelapseCount()));
        }
        return sb.toString();
    }

    /**
     * 把同岗位历史面试摘要（session_id/overall_score/date）组装成一段自然语言上下文，
     * 供出题规划 Phase1 感知"候选人是否多次面试过该岗位、分数趋势如何"。
     * 记录按时间倒序传入，这里反转为时间正序展示，并计算相邻两次的分差。
     */
    static String buildInterviewHistoryContext(List<UserProfile.InterviewRecord> recordsDesc) {
        if (recordsDesc == null || recordsDesc.isEmpty()) {
            return "";
        }
        List<UserProfile.InterviewRecord> recordsAsc = new ArrayList<>(recordsDesc);
        Collections.reverse(recordsAsc);

        List<String> lines = new ArrayList<>();
        Double prevScore = null;
        for (int i = 0; i < recordsAsc.size(); i++) {
            UserProfile.InterviewRecord r = recordsAsc.get(i);
            String dateStr = r.getDate() != null ? r.getDate().toLocalDate().toString() : "未知日期";
            StringBuilder line = new StringBuilder(String.format("- 第 %d 次（%s）：综合得分 %.0f",
                    i + 1, dateStr, r.getOverallScore()));
            if (prevScore != null) {
                double diff = r.getOverallScore() - prevScore;
                line.append(String.format("（较上次%s%.0f分）", diff >= 0 ? "+" : "", diff));
            }
            lines.add(line.toString());
            prevScore = r.getOverallScore();
        }
        return String.join("\n", lines);
    }
}
