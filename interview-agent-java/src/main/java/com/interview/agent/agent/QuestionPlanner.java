/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.mcp.QuestionBankTool;
import com.interview.agent.mcp.WebSearchTool;
import com.interview.agent.model.*;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionPlanner {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * B1：题库检索工具（复用 Milvus/BM25/Reranker）。存在且可用时，basic 类方向的出题
     * 改为交给 ReactAgent 自主决定检索策略（Agentic RAG）；不可用/异常时降级为
     * {@code Orchestrator} 里原有的手写 pipeline（一次检索、命中即用、不中转 LLM 兜底）。
     */
    @Setter
    @Autowired(required = false)
    private QuestionBankTool questionBankTool;

    /**
     * Router 升级：网页搜索工具（题库检索之外的第二个、异构的检索源）。只有配置了
     * app.websearch.api-key（博查AI搜索 Key）时才会被注册为 Bean（与 questionBankTool 同一套
     * "可选工具"模式）；不存在时出题 Agent 退化为只有题库一个来源，即上一版的纠正式
     * （Corrective）单工具 Agentic RAG，主流程不受影响。
     */
    @Setter
    @Autowired(required = false)
    private WebSearchTool webSearchTool;

    /**
     * B4：审题 Agent（Critic）。与出题 Agent 构成 Multi-Agent 协作——出题者产出草稿，
     * 审题者独立批判，被打回的方向回炉重出。
     * <p>
     * 与两个检索工具不同，它<b>不是可选依赖</b>（不依赖任何外部 API Key，只用已有的 ChatModel），
     * 所以直接必填注入；容错发生在更内层——{@link QuestionReviewer#review} 本身是 fail-open 的，
     * 模型调用失败或输出无法解析时返回空缺陷列表，等价于"本档全部通过"。
     * 用 {@code @Setter} 暴露是为了便于单元测试注入替身。
     */
    @Setter
    @Autowired
    private QuestionReviewer questionReviewer;

    @Setter
    @Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * B4：审题回炉最多轮数（与 {@code ReviewPlanner.MAX_REFLECT_ROUNDS} 保持一致）。
     * <p>
     * 为什么必须截断：LLM 做 Critic 时有一个常见失败模式——"总能再挑出点毛病"，
     * 不设上限会导致无限回炉、token 爆炸、面试迟迟无法开始。第 2 轮之后即便仍有打回意见
     * 也直接采纳当前草稿（fail-open），因为审题是质量增强而非准入门槛。
     */
    private static final int MAX_REVIEW_ROUNDS = 2;

    /**
     * B5：Phase 1 配额补全最多轮数。
     * <p>
     * 为什么只补 1 轮：配额补不上来，通常<b>不是模型不努力，而是输入里真没料</b>
     * （实习生简历撑不起 12 个 experience 方向）。再问 5 轮只会让它开始编，
     * 而编造的 experience 方向直接违反「严禁幻觉」——面试官会拿着简历里不存在的项目去问，
     * 危害远大于少几个方向。所以：宁可缺，不能编。
     */
    private static final int MAX_QUOTA_FILL_ROUNDS = 1;

    // ============================================================
    // Phase 1：规划出题方向
    // ============================================================
    private static final String DIRECTION_PLANNER_PROMPT = """
            你是一个资深的技术面试出题规划专家。根据 JD 分析和简历匹配结果，规划面试的出题方向。
            你的任务是：为每道题确定一个考察方向/考点，而不是出具体的题目。

            ====================【最重要：数量硬性约束，每个类型都要按难度分档铺满】====================
            你输出的 directions 数组中，按 type + difficulty 统计的数量必须严格满足：
            %s

            ⚠️ 为什么每档都必须铺满：以上是面试用的【候选题池】，面试时会按候选人的实时表现
            自适应抽取对应难度的题目（答得好升档、答得差降档），并不要求把候选题全部问完。
            因此每个难度档都必须铺满足够的候选方向——同一难度若只有一两个候选，连续答对/答错时
            就会无题可抽、难度调节形同虚设。所以宁可多铺，也不能让某个档位缺题。
            另外，basic（基础知识）方向是面试【题库原题】的唯一来源——每个 basic 方向都会去候选人
            题库里检索一道匹配的原题，basic 铺满也能让命中的题库原题更多、出题质量更高。
            如果候选人简历直接提到的知识点不足以铺满，就结合 JD 要求里的核心技术栈、以及该岗位
            常见的各档基础/经验/设计考点继续补充独立方向，直到每个档位的配额都达标为止。
            ===================================================================================

            题型说明（出题方向以候选人简历的技术栈和项目经历为主，JD 要求为辅）：
            - basic：核心技术知识点（语言特性、框架原理、中间件、数据库、并发、网络、操作系统等），
              每个知识点拆成一个独立方向；优先覆盖简历与 JD 共同涉及的技术栈
            - experience：针对简历中的工作 / 实习 / 项目经历的考察方向（必须基于简历真实内容）
            - design：系统设计、架构设计类方向，结合简历项目背景

            其他要求：
            1. 每个方向给出一个用于题库检索的关键词（search_query），要简洁精准（如"MySQL索引优化"、"Go channel原理"）
            2. experience 类方向必须基于简历中的真实信息，context 字段填写简历中的相关内容摘要
            3. 每个方向的 difficulty 必须标注准确，且严格符合上面按难度分档的数量配额（同一 type 下 easy/medium/hard 的方向数量必须达标）
            4. 【严禁幻觉】experience 类必须严格基于简历中的真实信息，不得杜撰或假设简历中未提及的技术细节
            5. 如果提供了"历史面试趋势"（候选人此前面试过同一岗位），结合分数趋势调整策略：
               - 若历史分数已较高/持续进步，对应领域适度提高难度档位占比，避免重复过于基础的方向
               - 若历史分数持续低迷或与上次接近，保留/加强该领域方向，用于验证薄弱点是否真正改善
               - 该信息仅用于调整方向的难度/侧重，不改变上面第①条的数量硬性约束

            请按以下 JSON 格式输出（不要输出其他任何内容）。
            ‼️ 输出前请自检一遍：%s 如不满足，必须调整后再输出。

            {
              "directions": [
                {
                  "topic": "考察方向描述（如：Go sync.Map 的并发安全机制）",
                  "type": "basic/experience/design",
                  "difficulty": "easy/medium/hard",
                  "search_query": "题库检索关键词（如：sync.Map 并发）",
                  "skills": ["考察的技能点"],
                  "context": "简历中相关上下文（experience 类必填，其他类型可为空）"
                }
              ]
            }""".formatted(DirectionQuotaChecker.describeQuota(), DirectionQuotaChecker.describeSelfCheck());

    /**
     * B5：配额缺口的定向补全 Prompt。
     * <p>
     * 与首轮的关键差异：<b>这是一个简单得多的任务</b>。首轮要同时满足语义质量与 9 个格子的
     * 计数约束，而计数正是 LLM 最弱的一环；补全只需针对 1~2 个格子产出少量方向，
     * 不必重新组织整体结构，成功率高得多。这也是为什么这里用「定向补全」
     * 而不是「整体重试」——整体重试会把已经出得不错的方向一起扔掉，token 翻倍，
     * 还可能在别的格子新增违约、来回震荡。
     */
    private static final String DIRECTION_GAP_FILLER_PROMPT = """
            你是一个资深的技术面试出题规划专家。此前已经规划了一批出题方向，但某些
            (type, difficulty) 组合的数量不足。现在只需补齐【缺口清单】里指定的方向。

            硬性要求：
            1. 严格按缺口清单给出的 type 与 difficulty 输出，数量必须精确匹配；
               不要补充清单之外的任何组合，也不要重复输出【已有方向】里的内容
            2. topic 不得与【已有方向】中的任何一条重复或语义等价（换个说法也算重复）
            3. difficulty 必须名副其实：easy 考基本概念与用法，medium 考原理与权衡，
               hard 考底层机制、边界场景或复杂取舍
            4. 【严禁杜撰】experience 类必须严格基于简历中真实存在的项目 / 实习 / 工作内容，
               context 字段填写简历中的原文摘要。如果简历内容确实不足以支撑要求的数量，
               就【少输出几条】——绝对不允许编造简历里不存在的项目、技术栈或细节。
               宁可缺方向，也不能让面试官拿着不存在的经历去提问。
            5. 输出格式与原规划完全一致，不要输出任何解释性文字

            {
              "directions": [
                {
                  "topic": "考察方向描述",
                  "type": "basic/experience/design",
                  "difficulty": "easy/medium/hard",
                  "search_query": "题库检索关键词",
                  "skills": ["考察的技能点"],
                  "context": "简历中相关上下文（experience 类必填，其他类型可为空）"
                }
              ]
            }""";

    // ============================================================
    // Phase 2：组装最终题目
    // ============================================================
    private static final String QUESTION_ASSEMBLER_PROMPT = """
            你是一个资深的技术面试出题专家。根据出题方向和题库匹配结果，生成最终的面试题目。

            规则：
            1. 【数量严格对应，最重要的规则】每个出题方向必须对应生成恰好一道题目，不得合并、删减或跳过任何方向。输入 N 个方向就必须输出 N 道题
            2. 如果提供了题库匹配的原题，直接使用原题（content 完全照搬不得改编），source 填题目 ID
            3. 如果没有匹配到题库原题，由你根据出题方向自行出题，source 填 "llm"
            4. 【LLM 出题基于简历】当 LLM 自行出题时，必须结合候选人简历的技术栈和项目经历来出题，确保题目与候选人背景相关
            5. 【严禁幻觉】experience 类题目必须严格基于简历中的真实信息提问，不得杜撰
            6. 题目 content 必须简洁精炼，一句话直击考察要点
            7. 每道题准备 1-2 个追问，用于深入考察
            8. 【难度沿用】每道题的 difficulty 必须与其对应出题方向给定的 difficulty 完全一致，不得更改，以保持整体难度分布的梯度

            请按以下 JSON 格式输出（不要输出其他内容）：

            {
              "total_questions": 10,
              "distribution": {
                "basic": 0,
                "experience": 0,
                "design": 0
              },
              "questions": [
                {
                  "id": "q1",
                  "content": "题目内容",
                  "type": "basic/experience/design",
                  "difficulty": "easy/medium/hard",
                  "skills": ["考察的技能点"],
                  "follow_ups": ["追问1", "追问2"],
                  "reference": "参考答案要点",
                  "source": "题库原题ID 或 llm"
                }
              ]
            }""";

    /**
     * Phase 1：规划出题方向。
     *
     * <p><b>B5：产出后加一层「校验 → 定向补全 → 兜底」</b>。原实现拿到 LLM 输出后只有一行
     * {@code log.info(size())}，配额（(type × difficulty) 9 个格子）完全靠 Prompt 自觉遵守，
     * 而逐格计数恰是 LLM 最弱的一环；违约又会被下游 {@code QuestionPool} 的 fallback 静默吸收，
     * 导致「动态难度调节失效」这种问题在线上跑一百场也发现不了。详见
     * {@link DirectionQuotaChecker} 的类注释。
     *
     * <p>三层处理里<b>前两层是零 token 的纯规则</b>，只有「某格子缺量」才需要多花一次模型调用：
     * <ol>
     *   <li>{@code normalize}：剔除 topic 缺失、type/difficulty 非法的方向（否则会被
     *       {@code QuestionPool} 静默归入 medium 桶）；</li>
     *   <li>{@code trimOverflow}：裁掉超额格子。<b>必须先裁再算缺口</b>，否则「hard 多 5 条 +
     *       easy 少 5 条」会互相抵消，补完总数超标；</li>
     *   <li>{@code diff} 仍有缺口 → 定向补全（最多 {@link #MAX_QUOTA_FILL_ROUNDS} 轮）；
     *       补完仍缺则 <b>fail-open</b>：记 warn 并按现状继续。</li>
     * </ol>
     *
     * <p><b>为什么这里 fail-open，而记忆写入门控却严格拦截</b>：判断依据是<b>后果是否可逆</b>。
     * 脏证据写进长期记忆会跨会话污染后续所有出题，不可逆，必须在入口拦死；
     * 方向缺几条只影响本场面试的难度调节精度，可逆，那就不能因此让整场面试起不来——
     * 配额是质量增强，不是准入门槛。这与 {@code QuestionReviewer} 审题失败视为全部通过同一取向。
     *
     * @param weakPoints        历史薄弱点上下文（跨会话，来自 LongTermMemory + MemoryRecallService）
     * @param interviewHistory  历史面试趋势上下文（同岗位的历次面试摘要，可为空）
     */
    public QuestionDirectionPlan planDirections(JDAnalysis jd, ResumeMatchResult match,
                                                 String weakPoints, String interviewHistory) {
        log.info("[QuestionPlanner] Phase 1: 规划出题方向");

        String baseContext = buildPlanContext(jd, match, weakPoints, interviewHistory);
        QuestionDirectionPlan plan = callDirectionPlanner(baseContext);

        List<QuestionDirection> dirs = DirectionQuotaChecker.normalize(plan.getDirections());
        dirs = DirectionQuotaChecker.trimOverflow(dirs);

        for (int round = 1; round <= MAX_QUOTA_FILL_ROUNDS; round++) {
            Map<String, Integer> gap = DirectionQuotaChecker.diff(dirs);
            if (gap.isEmpty()) {
                break;
            }
            log.warn("[QuestionPlanner] Phase 1 配额不达标（第 {} 轮补全前）：{}",
                    round, DirectionQuotaChecker.describeGap(gap));

            List<QuestionDirection> filled = callGapFiller(gap, dirs, baseContext);
            if (filled.isEmpty()) {
                // 补不出来通常是简历/JD 里真没料，继续追问只会让模型开始编，直接停手
                log.warn("[QuestionPlanner] 第 {} 轮补全未产出有效方向，停止补全", round);
                break;
            }
            dirs.addAll(filled);
            dirs = DirectionQuotaChecker.trimOverflow(dirs);
        }

        Map<String, Integer> finalGap = DirectionQuotaChecker.diff(dirs);
        if (finalGap.isEmpty()) {
            log.info("[QuestionPlanner] Phase 1 完成，规划了 {} 个出题方向（配额达标）", dirs.size());
        } else {
            // fail-open：显式记录降级代价，而不是让 QuestionPool 的 fallback 把问题静默吸收
            log.warn("[QuestionPlanner] Phase 1 完成但配额仍有缺口：{}；按现有 {}/{} 个方向继续。"
                            + "缺口对应的难度档在面试中会触发 QuestionPool 就近取题（fallback），"
                            + "该档的难度调节精度下降",
                    DirectionQuotaChecker.describeGap(finalGap), dirs.size(),
                    DirectionQuotaChecker.expectedTotal());
            if (meterRegistry != null) {
                finalGap.forEach((cell, gap) ->
                        meterRegistry.counter("question.quota.fill",
                                "cell", cell,
                                "gap", String.valueOf(gap),
                                "result", "fail_open").increment());
            }
        }
        logWeakPointCoverage(dirs, weakPoints);

        plan.setDirections(dirs);
        return plan;
    }

    /** 首轮方向规划的 LLM 调用（沿用原有重试语义）。 */
    private QuestionDirectionPlan callDirectionPlanner(String userMsg) {
        return AgentUtils.callWithRetry("出题方向规划", AgentUtils.DEFAULT_MAX_ATTEMPTS, () -> {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(DIRECTION_PLANNER_PROMPT),
                    new UserMessage(userMsg)
            ));

            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            String json = AgentUtils.extractJSON(content);

            try {
                QuestionDirectionPlan parsed = objectMapper.readValue(json, QuestionDirectionPlan.class);
                if (parsed.getDirections() == null) {
                    parsed.setDirections(new ArrayList<>());
                }
                log.info("[QuestionPlanner] Phase 1 首轮产出 {} 个方向，分布={}",
                        parsed.getDirections().size(),
                        DirectionQuotaChecker.countByCell(parsed.getDirections()));
                return parsed;
            } catch (Exception e) {
                throw new RuntimeException("出题方向解析失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * B5：定向补全缺口格子。
     *
     * <p><b>fail-soft</b>：补全本身失败（模型异常、JSON 解析失败）只返回空列表并打 warn，
     * 不向上抛——补全是对首轮结果的增强，不能让它成为整条流程的新故障点。因此这里
     * 刻意<b>不套</b> {@code AgentUtils.callWithRetry}：重试会放大延迟，而拿不到补全的代价
     * 只是配额缺几条（已由外层 fail-open 承接）。
     */
    private List<QuestionDirection> callGapFiller(Map<String, Integer> gap,
                                                   List<QuestionDirection> existing,
                                                   String baseContext) {
        try {
            StringBuilder msg = new StringBuilder();
            msg.append("## 缺口清单（只补这些，数量必须精确）\n");
            gap.forEach((key, need) -> {
                String[] parts = key.split("/");
                msg.append(String.format("- type=%s，difficulty=%s，还需 %d 个%n",
                        parts[0], parts[1], need));
            });

            // 只传 topic 做去重约束：去重只需要 topic，传完整方向 JSON 会让 prompt 膨胀数倍，
            // 还容易让模型误以为需要复述已有内容。
            msg.append("\n## 已有方向的 topic 列表（不得重复或语义等价）\n");
            for (QuestionDirection d : existing) {
                msg.append("- ").append(d.getTopic()).append('\n');
            }

            if (DirectionQuotaChecker.gapTypes(gap).contains("experience")) {
                msg.append("\n⚠️ 本次缺口包含 experience 类：必须严格基于下方简历中真实存在的内容，"
                        + "简历撑不住就少给几条，绝不允许编造。\n");
            }

            msg.append('\n').append(baseContext);

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(DIRECTION_GAP_FILLER_PROMPT),
                    new UserMessage(msg.toString())
            ));
            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null) {
                log.warn("[QuestionPlanner] 配额补全无响应");
                return new ArrayList<>();
            }
            String json = AgentUtils.extractJSON(response.getResult().getOutput().getText());
            QuestionDirectionPlan parsed = objectMapper.readValue(json, QuestionDirectionPlan.class);

            List<QuestionDirection> filled = DirectionQuotaChecker.normalize(parsed.getDirections());
            filled = DirectionQuotaChecker.dedup(filled, existing);
            log.info("[QuestionPlanner] 配额补全产出 {} 个有效方向，分布={}",
                    filled.size(), DirectionQuotaChecker.countByCell(filled));
            return filled;
        } catch (Exception e) {
            log.warn("[QuestionPlanner] 配额补全失败（不影响主流程，将按现状继续）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 可观测性补充：统计「被判为强相关的历史薄弱点」有多少真的被方向覆盖。
     *
     * <p>此前召回排出的薄弱点进 Prompt 后，模型采纳了几条<b>完全不可观测</b>——极端情况一条不用
     * 也不会有任何信号。没有这个指标，「候选池给多少条才够」这类问题就只能靠猜。
     * 这里只统计不干预：覆盖率低说明召回结果对最终出题影响有限，是后续优化的输入，
     * 而不是应该在本轮强行纠正的东西。
     */
    private void logWeakPointCoverage(List<QuestionDirection> dirs, String weakPointsContext) {
        if (weakPointsContext == null || weakPointsContext.isBlank()) {
            return;
        }
        List<String> strongTopics = new ArrayList<>();
        for (String line : weakPointsContext.split("\n")) {
            if (!line.contains("[强相关]")) {
                continue;
            }
            // 渲染格式见 Orchestrator.describeWeakPoint：「- [强相关] {topic}：最近得分 ...」
            int start = line.indexOf("[强相关]") + "[强相关]".length();
            int end = line.indexOf('：', start);
            String topic = (end > start ? line.substring(start, end) : line.substring(start)).trim();
            if (!topic.isEmpty()) {
                strongTopics.add(topic);
            }
        }
        if (strongTopics.isEmpty()) {
            return;
        }
        List<String> missed = new ArrayList<>();
        for (String topic : strongTopics) {
            if (!DirectionQuotaChecker.covers(dirs, topic)) {
                missed.add(topic);
            }
        }
        int covered = strongTopics.size() - missed.size();
        double coverage = (double) covered / strongTopics.size();
        if (meterRegistry != null) {
            meterRegistry.summary("interview.weakpoint.coverage").record(coverage);
        }
        if (missed.isEmpty()) {
            log.info("[QuestionPlanner] 强相关薄弱点覆盖率 {}/{}（全部被出题方向覆盖）",
                    covered, strongTopics.size());
        } else {
            log.info("[QuestionPlanner] 强相关薄弱点覆盖率 {}/{}，未覆盖: {}",
                    covered, strongTopics.size(), missed);
        }
    }

    /** 组装 Phase 1 的上下文（首轮与补全轮共用，保证补全时模型看到的信息一致）。 */
    private String buildPlanContext(JDAnalysis jd, ResumeMatchResult match,
                                    String weakPoints, String interviewHistory) {
        StringBuilder userMsg = new StringBuilder();
        try {
            userMsg.append("## JD 分析结果\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jd));
            userMsg.append("\n\n## 简历匹配结果\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(match));
        } catch (Exception e) {
            userMsg.append(jd.toString()).append("\n").append(match.toString());
        }

        if (weakPoints != null && !weakPoints.isEmpty()) {
            userMsg.append("\n\n## 历史薄弱点（需重点考察）\n").append(weakPoints);
        }

        if (interviewHistory != null && !interviewHistory.isEmpty()) {
            userMsg.append("\n\n## 历史面试趋势（同岗位）\n").append(interviewHistory);
        }
        return userMsg.toString();
    }

    /**
     * Phase 2：按方向组装最终题目
     */
    public QuestionPlan assembleQuestions(JDAnalysis jd, ResumeMatchResult match,
                                          QuestionDirectionPlan directions, List<String> directionDocs) {
        log.info("[QuestionPlanner] Phase 2: 组装题目，{} 个方向", directions.getDirections().size());

        StringBuilder userMsg = new StringBuilder();
        userMsg.append("## 出题方向\n");
        List<QuestionDirection> dirs = directions.getDirections();
        for (int i = 0; i < dirs.size(); i++) {
            QuestionDirection dir = dirs.get(i);
            userMsg.append(String.format("\n### 方向 %d\n", i + 1));
            userMsg.append(String.format("- 考点: %s\n- 类型: %s\n- 难度: %s\n", dir.getTopic(), dir.getType(), dir.getDifficulty()));

            String doc = (directionDocs != null && i < directionDocs.size()) ? directionDocs.get(i) : "";
            if (doc != null && !doc.isEmpty()) {
                userMsg.append(String.format("- 题库匹配:\n%s\n", doc));
            } else {
                userMsg.append("- 题库匹配: 无匹配，请自行出题\n");
            }
        }

        try {
            userMsg.append("\n## JD 分析（出题参考）\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jd));
            userMsg.append("\n\n## 简历匹配（出题参考）\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(match));
        } catch (Exception ignored) {}

        return AgentUtils.callWithRetry("题目组装", AgentUtils.DEFAULT_MAX_ATTEMPTS, () -> {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(QUESTION_ASSEMBLER_PROMPT),
                    new UserMessage(userMsg.toString())
            ));

            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            String json = AgentUtils.extractJSON(content);

            try {
                QuestionPlan plan = objectMapper.readValue(json, QuestionPlan.class);
                log.info("[QuestionPlanner] Phase 2 完成，共 {} 道题", plan.getQuestions().size());
                return plan;
            } catch (Exception e) {
                throw new RuntimeException("题目组装解析失败: " + e.getMessage(), e);
            }
        });
    }

    // ============================================================
    // B1：Agentic RAG —— basic 类方向的出题改为交给 ReactAgent 自主决策
    //
    // 按 difficulty 分组（而不是每个方向 new 一个 agent）：agent 实例数量恒定为
    // easy/medium/hard 三档，不随方向数量（未来可能因跨专业扩展到几十个方向）线性增长；
    // 每次调用把同一难度档下的全部方向一次性交给一个 agent，agent 在一轮会话里对每个方向
    // 依次调用 search_question_bank（ReAct 循环本身支持一次会话多次工具调用），最终一次性
    // 输出这一档所有方向的题目，通过 direction_index 显式回填对应关系。
    //
    // Router 升级：当 webSearchTool 可用时，再给这个 agent 挂第二个异构检索源 search_web
    // （公开网络）。此时 agent 的决策不再只是"该不该信任题库检索结果、要不要换词重试"这种
    // 单一来源内部的纠正式（Corrective）循环，而是真正要在【专属题库】和【公开网络】两个
    // 不同来源之间做路由选择——这才是 Single-Agent Agentic RAG 里 Router 子类型的核心语义。
    // webSearchTool 不可用时，两套指令文案（{@link #buildGroupAgentInstruction}）自动退化为
    // 只提及 search_question_bank 的版本，行为与升级前完全一致。
    //
    // ------------------------------------------------------------
    // B4：Multi-Agent 升级 —— 出题 Agent + 审题 Agent 的带回环协作
    //
    // 【为什么拆】上面的单 agent 架构有一个结构性缺陷：出题者同时充当自己的质检员。
    // 而"生成"与"批判"是天然对立的立场，让同一个 agent 评价自己刚写的题存在强烈确认偏差；
    // 更关键的是，有几类问题它在结构上就发现不了——题库检索是逐方向独立进行的，邻近方向
    // （如"MySQL索引优化"与"MySQL B+树"）很容易命中实质相同的原题，但出题 agent 的注意力
    // 始终在"逐个方向查库"，不会回头做全局比对；题库原题的 difficulty 是题库自己标的，
    // 照搬进来可能一个 hard 方向拿到一道实际只考 API 用法的题，直接破坏 StageScheduler
    // 依赖的难度梯度。
    //
    // 【拆成什么】三层职责，边界互不重叠：
    //   1) 出题 Agent（question_assembler_group，持有检索工具）：检索、选源、产出题目
    //   2) 规则预检层（QuestionRuleChecker，纯 Java，零 token）：机械可判定的硬错误
    //      —— 难度标注不一致、follow_ups 缺失、题干过短、跨方向文本近重复。
    //      能确定性判定的问题绝不花模型的钱，也不存在 LLM 判重的随机误判。
    //   3) 审题 Agent（QuestionReviewer，无任何工具）：只做需要语义理解的批判
    //      —— 考点是否跑偏、难度是否名副其实、追问是否递进、事实是否可疑、语义是否重复。
    //
    // 【怎么协作】两个通信通道，分工明确：
    //   · 横向（跨 agent）：Graph State 作为 Blackboard 传结构化数据，以 direction_index
    //     为 join key。出题是确定性生成任务，需要可校验、可精确回填的结构化 verdict，
    //     而不是自然语言协商——模型说"第三道题有问题"时无法确定它数的是哪一道。
    //   · 纵向（单 agent 跨轮）：出题 Agent 的 List<Message> 对话历史持续累积，回炉时
    //     复用【同一个 agent 实例】+ 历史，它才能看到自己上一轮写了什么；打回理由作为新的
    //     UserMessage 追加进去（与 ReviewPlanner B2 反思循环同一套写法）。
    //
    // 【回环的真实价值】打回理由能驱动出题 Agent 发起【新的检索】，而不只是把文本重写一遍。
    // 例如"方向4与方向1重复"这条理由，会让它换关键词重新调 search_question_bank——
    // 这是单 agent 自评做不到的（自评时它没有动机认为自己需要重查）。
    //
    // 【工程约束】
    //   · 只回炉被打回的方向，已 pass 的不动：省 token、省检索调用，也避免重出时把好题改坏；
    //   · MAX_REVIEW_ROUNDS=2 截断：LLM 做 Critic "总能再挑出点毛病"，不截断会无限回炉；
    //   · 全程 fail-open：Critic 异常、解析失败、轮次耗尽仍有打回 —— 一律接受当前草稿。
    //     审题是【质量增强】而不是【准入门槛】，不能因为 Critic 挂了就让面试没题可问。
    // ============================================================

    // 注意：instruction 会被框架按 f-string 模板渲染（{x} 视为占位符），因此这里不能出现裸 {}。
    private static final String BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_BASE = """
            你是资深技术面试出题专家，本次任务是为【同一难度档】下的多个基础知识考察方向各产出恰好一道面试题。

            你可以使用 search_question_bank 工具：输入一个检索关键词，从候选人专属题库里检索最相似的
            候选题目（含相似度、内容、参考答案）。这是你的【首选/权威来源】——题库题目都是候选人专属、
            经过审核的高质量原题，优先级高于你自己出题或检索网络。你需要针对下面列出的【每一个】方向都
            尝试检索一次（可以在本轮对话里连续多次调用本工具，每次换一个方向的关键词）。""";

    private static final String BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_WEB_PART = """


            你还可以使用 search_web 工具：输入一个检索关键词，检索公开网页获取相关资料
            （标题、链接、摘要）。这是【补充来源】，只有满足以下条件才路由到它，不要随意使用：
            - 该方向在题库里换词重试后仍无满意匹配，并且属于你自己把握不太准的新/冷门技术点
              （比如某个框架的新版本特性、某个小众开源项目、某个刚出现不久的技术名词），
              需要先查证一下再出题，避免凭空编造过时或错误的技术细节；
            - search_web 只用于查证技术事实、辅助你自己出题，不要拿它去找"现成的面试题"，
              查到资料后仍需由你自己组织出最终题目（source 填 "web:<对应链接>"）；
            - 如果这个方向是常规基础知识点，题库结果或你自身知识已经足够准确，不需要调用
              search_web，不要每个方向都去搜网页。""";

    private static final String BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_TAIL_COMMON = """


            ⚠️ 极其重要（否则结果无法回填，会被当作出题失败）：
            1. 下面给出的每个方向都带有一个 direction_index，你输出对应题目时必须原样带回这个
               direction_index，用于精确回填对应方向——不能靠数组顺序或模糊比对考点文本去猜。
            2. 【方向数量必须对应】下面列出了 N 个方向，你就必须输出 N 道题，一个不漏；即使某个
               方向没有检索到合适的原题，也不要跳过它，而是结合该方向自行出题（source 填 "llm"），
               确保每一个 direction_index 都出现在最终结果里。
            3. 每道题的 difficulty 必须与其对应方向给定的难度完全一致（本轮所有方向难度相同）。

            题目要求：content 简洁精炼，一句话直击考察点；准备1-2个追问；如果使用了题库原题或网络资料，
            reference 用对应的参考答案/依据查证资料给出参考答案要点，否则自行给出参考答案要点。""";

    private static final String BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_OUTPUT_WITH_WEB = """


            最终只输出一个纯 JSON 对象（不要输出思考过程、工具调用说明或多余文字）：
            {
              "questions": [
                {
                  "direction_index": 0,
                  "content": "题目内容",
                  "difficulty": "easy/medium/hard",
                  "follow_ups": ["追问1", "追问2"],
                  "reference": "参考答案要点",
                  "source": "题库原题ID 或 llm 或 web:<对应链接>"
                }
              ]
            }""";

    private static final String BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_OUTPUT_NO_WEB = """


            最终只输出一个纯 JSON 对象（不要输出思考过程、工具调用说明或多余文字）：
            {
              "questions": [
                {
                  "direction_index": 0,
                  "content": "题目内容",
                  "difficulty": "easy/medium/hard",
                  "follow_ups": ["追问1", "追问2"],
                  "reference": "参考答案要点",
                  "source": "题库原题ID 或 llm"
                }
              ]
            }""";

    /**
     * 按 webSearchTool 是否可用拼出最终指令：可用时是"题库+网络"双来源 Router 版本，
     * 不可用时退化为只提及题库的单来源纠正式版本——两个版本发给模型的工具能力描述必须
     * 与实际绑定的 tools 列表严格一致，否则模型可能尝试调用一个根本没注册的工具。
     */
    private static String buildGroupAgentInstruction(boolean hasWebSearch) {
        if (hasWebSearch) {
            return BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_BASE
                    + BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_WEB_PART
                    + BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_TAIL_COMMON
                    + BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_OUTPUT_WITH_WEB;
        }
        return BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_BASE
                + BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_TAIL_COMMON
                + BASIC_QUESTION_GROUP_AGENT_INSTRUCTION_OUTPUT_NO_WEB;
    }

    /**
     * B1 + B4：同一难度档下一组 basic 方向的题目组装。
     * <p>
     * <b>B1</b>：从"每个方向 new 一个 ReactAgent"改为"每个难度档（固定 3 档）复用一个 agent 会话"，
     * agent 实例数量恒定，不随方向数量增长；模型在一轮 ReAct 循环里对组内每个方向依次决定
     * 检索关键词、要不要重试、最终用原题还是自己出题。Router 升级后 {@code webSearchTool} 可用时，
     * 该 agent 还能在【专属题库】和【公开网络】两个异构来源之间自主路由。
     * <p>
     * <b>B4</b>：本方法内部升级为一张带条件回环的 {@link StateGraph}，用出题 Agent 与审题 Agent
     * 两个独立角色协作产出题目：
     * <pre>
     *   START → assemble ──→ review ──[无打回 或 轮次耗尽]──→ finalize → END
     *              ↑            │
     *              └────────────┘  [有打回 且 round &lt; MAX_REVIEW_ROUNDS]
     * </pre>
     * 其中 {@code review} 节点先跑零 token 的规则预检（{@link QuestionRuleChecker}），再跑
     * 语义批判的审题 Agent（{@link QuestionReviewer}），两者的缺陷合并后回喂给出题 Agent。
     * <p>
     * <b>对外契约完全不变</b>（{@code Orchestrator} 无需任何改动）：返回
     * {@code Map<组内下标, PlannedQuestion>}，key 是入参 {@code dirs} 列表内的下标（不是全局方向
     * 下标，由调用方自行换算）。整组调用失败/题库不可用时返回空 map；即使调用成功，也可能只覆盖到
     * dirs 的一部分下标（模型漏了某个方向）——调用方需要对没有出现在返回 map 里的下标，单独降级为
     * 原有手写 pipeline，而不是让"一个方向没覆盖到"拖累整组重新降级。
     */
    public Map<Integer, PlannedQuestion> assembleBasicQuestionsWithAgent(List<QuestionDirection> dirs, String userId) {
        if (questionBankTool == null || !questionBankTool.isAvailable() || dirs == null || dirs.isEmpty()) {
            return new HashMap<>();
        }
        String difficulty = dirs.get(0).getDifficulty();
        try {
            // Router 升级：只有 webSearchTool 真的可用（配置了 API Key）时才把它加进工具列表，
            // 并同步切换成"双来源"版本的指令——保证发给模型的工具能力描述与实际能调用的工具严格一致。
            boolean hasWebSearch = webSearchTool != null && webSearchTool.isAvailable();
            List<ToolCallback> tools = new ArrayList<>();
            tools.add(questionBankTool.asQuestionAssemblyTool(userId));
            if (hasWebSearch) {
                tools.add(webSearchTool.asRouterTool());
            }

            // 出题 Agent 在整张图的多轮回环里【复用同一个实例】，配合 GroupCtx.history 累积对话：
            // 这样它重出时能看到自己上一轮写了什么，打回理由才有的放矢（否则只能凭理由瞎猜）。
            GroupCtx ctx = new GroupCtx(dirs);
            ctx.assembler = ReactAgent.builder()
                    .name("question_assembler_group")
                    .model(chatModel)
                    .instruction(buildGroupAgentInstruction(hasWebSearch))
                    .tools(tools)
                    .build();

            // graph 执行节点前会对 state 做序列化快照，因此 state 里只放纯数据（轮次、缺陷条数），
            // agent 实例、对话历史、草稿这些业务对象放在闭包捕获的 GroupCtx 里
            //（与 Orchestrator.Ctx 同一套规避手法）。
            KeyStrategyFactory keyStrategyFactory = () -> {
                Map<String, KeyStrategy> keys = new HashMap<>();
                keys.put("round", new ReplaceStrategy());
                keys.put("defect_count", new ReplaceStrategy());
                return keys;
            };

            StateGraph graph = new StateGraph(keyStrategyFactory)
                    .addNode("assemble", node_async(s -> assembleNode(ctx)))
                    .addNode("review", node_async(s -> reviewNode(ctx)))
                    .addNode("finalize", node_async(s -> finalizeNode(ctx)))
                    .addEdge(START, "assemble")
                    .addEdge("assemble", "review")
                    .addConditionalEdges("review", edge_async(s -> needRework(ctx) ? "rework" : "done"),
                            Map.of("rework", "assemble", "done", "finalize"))
                    .addEdge("finalize", END);

            graph.compile().invoke(new HashMap<>());

            if (ctx.result.size() < dirs.size()) {
                log.info("[QuestionPlanner] 分组出题（难度{}）覆盖 {}/{} 个方向，剩余方向将单独降级为传统 pipeline",
                        difficulty, ctx.result.size(), dirs.size());
            }
            return ctx.result;
        } catch (Exception e) {
            log.warn("[QuestionPlanner] 分组 Agentic 出题失败（难度: {}，共 {} 个方向），整组降级为传统 RAG+LLM 流程: {}",
                    difficulty, dirs.size(), e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * experience/design 类方向的出题-审题子图入口。
     * <p>
     * 与 {@link #assembleBasicQuestionsWithAgent} 的区别：
     * <ul>
     *   <li>不挂检索工具（experience 必须基于简历真实内容，design 题库覆盖不足）</li>
     *   <li>出题走单轮 {@code chatModel.call} 而非 ReactAgent</li>
     *   <li>审题回环结构完全一致（assemble ⇄ review → finalize，MAX_REVIEW_ROUNDS=2）</li>
     * </ul>
     * GroupCtx.assembler 为 null 时，{@link #assembleNode} 自动走单轮 LLM 出题路径。
     */
    public Map<Integer, PlannedQuestion> assembleExpDesignWithReview(List<QuestionDirection> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return new HashMap<>();
        }
        String difficulty = dirs.get(0).getDifficulty();
        String type = dirs.get(0).getType();
        try {
            GroupCtx ctx = new GroupCtx(dirs);
            // assembler 为 null → assembleNode 走单轮 chatModel.call

            KeyStrategyFactory keyStrategyFactory = () -> {
                Map<String, KeyStrategy> keys = new HashMap<>();
                keys.put("round", new ReplaceStrategy());
                keys.put("defect_count", new ReplaceStrategy());
                return keys;
            };

            StateGraph graph = new StateGraph(keyStrategyFactory)
                    .addNode("assemble", node_async(s -> assembleNode(ctx)))
                    .addNode("review", node_async(s -> reviewNode(ctx)))
                    .addNode("finalize", node_async(s -> finalizeNode(ctx)))
                    .addEdge(START, "assemble")
                    .addEdge("assemble", "review")
                    .addConditionalEdges("review", edge_async(s -> needRework(ctx) ? "rework" : "done"),
                            Map.of("rework", "assemble", "done", "finalize"))
                    .addEdge("finalize", END);

            graph.compile().invoke(new HashMap<>());

            if (ctx.result.size() < dirs.size()) {
                log.info("[QuestionPlanner] {} 类出题（难度{}）覆盖 {}/{} 个方向，剩余方向将单独降级",
                        type, difficulty, ctx.result.size(), dirs.size());
            }
            return ctx.result;
        } catch (Exception e) {
            log.warn("[QuestionPlanner] {} 类审题出题失败（难度: {}，共 {} 个方向），整组降级: {}",
                    type, difficulty, dirs.size(), e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * <p>
     * 关键点是<b>复用同一个 agent 实例 + 累积的对话历史</b>：把打回理由作为新的 UserMessage
     * 追加进 {@code ctx.history} 再调用，模型就能看到"我上一轮给出的题 + 审核意见"，从而
     * 有针对性地重出——甚至会主动换关键词重新调用检索工具（这是打回理由能驱动【新一轮检索】
     * 而不只是重写文本的原因，也是单 agent 自评做不到的）。写法与 {@code ReviewPlanner}
     * B2 反思循环一致。
     */
    private Map<String, Object> assembleNode(GroupCtx ctx) {
        ctx.round++;
        boolean isRework = ctx.round > 1;

        String userMsg = isRework
                ? buildReworkMessage(ctx)
                : buildInitialAssembleMessage(ctx.dirs);
        ctx.history.add(new UserMessage(userMsg));

        try {
            String responseText;
            if (ctx.assembler != null) {
                // basic 类：走 ReactAgent（带检索工具）
                AssistantMessage msg = ctx.assembler.call(ctx.history);
                if (msg == null) {
                    log.warn("[QuestionPlanner] 出题 Agent 第 {} 轮无响应", ctx.round);
                    return Map.of("round", ctx.round);
                }
                ctx.history.add(msg);
                responseText = msg.getText();
            } else {
                // experience/design 类：走单轮 LLM（无工具，基于简历内容出题）
                Prompt prompt = new Prompt(List.of(
                        new SystemMessage(QUESTION_ASSEMBLER_PROMPT),
                        new UserMessage(userMsg)));
                ChatResponse response = chatModel.call(prompt);
                if (response == null || response.getResult() == null) {
                    log.warn("[QuestionPlanner] 出题 LLM 第 {} 轮无响应", ctx.round);
                    return Map.of("round", ctx.round);
                }
                responseText = response.getResult().getOutput().getText();
                ctx.history.add(new AssistantMessage(responseText));
            }
            int parsed = parseDraftInto(ctx, responseText);
            log.info("[QuestionPlanner] 出题 {} 第 {} 轮{}完成，本轮产出 {} 道题（累计覆盖 {}/{}）",
                    ctx.assembler != null ? "Agent" : "LLM",
                    ctx.round, isRework ? "（重出被打回方向）" : "", parsed, ctx.drafts.size(), ctx.dirs.size());
        } catch (Exception e) {
            // 重出失败时保留上一轮的草稿（fail-open）：宁可用一版有瑕疵的题，也不能没题可问
            log.warn("[QuestionPlanner] 出题 {} 第 {} 轮调用失败: {}",
                    ctx.assembler != null ? "Agent" : "LLM", ctx.round, e.getMessage());
        }
        return Map.of("round", ctx.round);
    }

    /**
     * B4 节点②：审题。两级串联——先跑零 token 的规则预检，再跑语义批判的审题 Agent。
     * <p>
     * 顺序上先规则后 Critic：规则层能确定性判定的硬错误（难度不一致、追问缺失、文本近重复）
     * 不需要花模型的钱，也不存在 LLM 判重的随机误判；Critic 则专注在真正需要理解语义的维度。
     * 两者的缺陷按 direction_index 合并后统一回喂，出题 Agent 无需关心某条意见来自哪一层。
     */
    private Map<String, Object> reviewNode(GroupCtx ctx) {
        ctx.defects.clear();
        if (ctx.drafts.isEmpty()) {
            return Map.of("defect_count", 0);
        }

        List<QuestionDefect> ruleDefects = QuestionRuleChecker.check(ctx.dirs, ctx.drafts);
        ctx.defects.addAll(ruleDefects);

        List<QuestionDefect> criticDefects = questionReviewer == null
                ? List.of()
                : questionReviewer.review(ctx.dirs, ctx.drafts);
        ctx.defects.addAll(criticDefects);

        // 已经被打回过、且这一轮又被打回的方向：只在最终轮次判定里体现，这里不做特殊处理，
        // 由 MAX_REVIEW_ROUNDS 统一截断，避免"反复打回同一道题"演变成无限循环。
        log.info("[QuestionPlanner] 第 {} 轮审题：规则层 {} 条 + 审题 Agent {} 条，共打回 {} 个方向",
                ctx.round, ruleDefects.size(), criticDefects.size(), rejectedIndices(ctx).size());
        return Map.of("defect_count", ctx.defects.size());
    }

    /**
     * B4 节点③：定稿。把最终草稿原样搬进对外返回的 {@code result}。
     * <p>
     * 单独设一个节点而不是在条件边上直接返回，是为了让"定稿"成为图上一个显式的、可观测的
     * 步骤——日志里能明确看到本档最终采纳了哪些来源，也方便未来在此处挂落库/埋点。
     */
    private Map<String, Object> finalizeNode(GroupCtx ctx) {
        ctx.result.putAll(ctx.drafts);
        if (!ctx.defects.isEmpty()) {
            // 轮次耗尽仍有打回意见时走到这里：fail-open 采纳当前版本，但留下日志便于事后复盘
            log.info("[QuestionPlanner] 审题轮次已达上限（{} 轮），仍有 {} 条未消化的意见，采纳当前版本",
                    MAX_REVIEW_ROUNDS, ctx.defects.size());
        }
        log.info("[QuestionPlanner] 分组出题定稿（难度{}）：共 {} 道题，经历 {} 轮出题-审题",
                ctx.dirs.get(0).getDifficulty(), ctx.result.size(), ctx.round);
        if (meterRegistry != null) {
            meterRegistry.counter("question.review.rounds",
                    "difficulty", ctx.dirs.get(0).getDifficulty(),
                    "rounds", String.valueOf(ctx.round)).increment();
        }
        return Map.of();
    }

    /**
     * B4 条件边：是否需要回炉重出。
     * <p>
     * 三个条件同时满足才回炉：有打回意见、轮次未耗尽、草稿非空。轮次上限的必要性见
     * {@link #MAX_REVIEW_ROUNDS}——LLM 做 Critic 时"总能再挑出点毛病"，不截断会无限循环。
     */
    private boolean needRework(GroupCtx ctx) {
        return !ctx.defects.isEmpty() && ctx.round < MAX_REVIEW_ROUNDS && !ctx.drafts.isEmpty();
    }

    /** 首轮出题的用户消息：列出本档所有方向及建议检索关键词 */
    private String buildInitialAssembleMessage(List<QuestionDirection> dirs) {
        StringBuilder userMsg = new StringBuilder();
        userMsg.append(String.format("## 本次难度档：%s，共 %d 个考察方向，请逐一处理\n",
                dirs.get(0).getDifficulty(), dirs.size()));
        for (int i = 0; i < dirs.size(); i++) {
            QuestionDirection dir = dirs.get(i);
            String suggestedQuery = (dir.getSearchQuery() != null && !dir.getSearchQuery().isEmpty())
                    ? dir.getSearchQuery() : dir.getTopic();
            userMsg.append(String.format("""

                    ### 方向 direction_index=%d
                    考点: %s
                    技能标签: %s
                    建议的检索关键词（可直接用，也可以换一个更合适的）: %s
                    """, i, dir.getTopic(), dir.getSkills(), suggestedQuery));
        }
        return userMsg.toString();
    }

    /**
     * 回炉重出的用户消息：只列出被打回的方向 + 具体理由。
     * <p>
     * <b>只回炉被打回的方向，已 pass 的一律不动</b>：一档 5 道题里 3 道合格的没必要重新生成，
     * 既省 token 与检索调用，也避免"重出时把原本好的题改坏"这种回归风险。
     * <p>
     * 消息里显式强调"允许重新检索"和"不得改编原题却仍标题库ID"两条：前者是为了让打回理由能
     * 驱动新一轮检索（比如"与方向1重复"应当促使它换关键词重查），后者是守住 source 字段的
     * 可信度底线——{@code Orchestrator.buildWeakReviewContent} 会依据 source 回题库取参考答案，
     * 一旦题干被改编却仍标着题库 ID，取回的答案就和题目对不上了。
     */
    private String buildReworkMessage(GroupCtx ctx) {
        Map<Integer, List<QuestionDefect>> byIndex = new LinkedHashMap<>();
        for (QuestionDefect d : ctx.defects) {
            byIndex.computeIfAbsent(d.getDirectionIndex(), k -> new ArrayList<>()).add(d);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                ## 审核结果：以下 %d 个方向的题目未通过审核，需要你重新出题

                其余方向的题目已通过审核，【不要重复输出】它们，本轮只输出下面列出的方向。
                """, byIndex.size()));

        for (Map.Entry<Integer, List<QuestionDefect>> e : byIndex.entrySet()) {
            int idx = e.getKey();
            QuestionDirection dir = ctx.dirs.get(idx);
            PlannedQuestion old = ctx.drafts.get(idx);
            sb.append(String.format("""

                    ### direction_index=%d（考点: %s ｜ 要求难度: %s）
                    你上一轮给出的题目: %s
                    上一轮的来源 source: %s
                    审核未通过的原因:
                    """, idx, dir.getTopic(), dir.getDifficulty(),
                    old == null ? "(无)" : old.getContent(),
                    old == null ? "(无)" : old.getSource()));
            for (QuestionDefect d : e.getValue()) {
                sb.append("  - ").append(d.getReason());
                if (d.getSuggestion() != null && !d.getSuggestion().isEmpty()) {
                    sb.append("（改进方向：").append(d.getSuggestion()).append("）");
                }
                sb.append("\n");
            }
        }

        sb.append("""

                ## 重出要求
                1. 如果打回原因涉及"题库原题不合适/与其他方向重复/技术细节存疑"，你可以并且应该
                   【重新调用检索工具】——换一个更贴合的关键词再查题库，或用网页检索去查证存疑的技术细节，
                   而不是只把原来的文字换个说法。
                2. 【source 必须诚实】若你保留了某道题库原题的原文，source 仍填该题库 ID；
                   若你对题干做了任何改编，或改为自行出题，source 必须改成 "llm"（或 "web:<链接>"）。
                   严禁改编了原题内容却仍然标注题库 ID。
                3. 输出格式与上一轮完全相同（同一个 JSON 结构），但 questions 数组里
                   【只包含本轮需要重出的 direction_index】。""");
        return sb.toString();
    }

    /** 解析出题 Agent 的输出并合并进 ctx.drafts（重出的方向会覆盖旧版本），返回本轮成功解析条数 */
    private int parseDraftInto(GroupCtx ctx, String text) {
        int count = 0;
        try {
            JsonNode root = objectMapper.readTree(AgentUtils.extractJSON(text));
            for (JsonNode node : root.path("questions")) {
                int idx = node.path("direction_index").asInt(-1);
                if (idx < 0 || idx >= ctx.dirs.size()) {
                    log.warn("[QuestionPlanner] 分组出题返回了非法 direction_index={}，忽略该条", idx);
                    continue;
                }
                QuestionDirection dir = ctx.dirs.get(idx);
                PlannedQuestion pq = new PlannedQuestion();
                pq.setContent(node.path("content").asText(dir.getTopic()));
                pq.setType("basic");
                pq.setDifficulty(node.path("difficulty").asText(dir.getDifficulty()));
                pq.setSkills(dir.getSkills());
                List<String> followUps = new ArrayList<>();
                node.path("follow_ups").forEach(n -> followUps.add(n.asText()));
                pq.setFollowUps(followUps);
                pq.setReference(node.path("reference").asText(""));
                pq.setSource(node.path("source").asText("llm"));
                ctx.drafts.put(idx, pq);
                count++;
            }
        } catch (Exception e) {
            log.warn("[QuestionPlanner] 解析出题 Agent 输出失败（第 {} 轮）: {}", ctx.round, e.getMessage());
        }
        return count;
    }

    /** 当前被打回的方向下标集合（一个方向可能有多条缺陷，去重后统计） */
    private static List<Integer> rejectedIndices(GroupCtx ctx) {
        List<Integer> list = new ArrayList<>();
        for (QuestionDefect d : ctx.defects) {
            if (!list.contains(d.getDirectionIndex())) {
                list.add(d.getDirectionIndex());
            }
        }
        return list;
    }

    /**
     * B4：一次"一档出题"的上下文持有者。
     * <p>
     * 这些字段<b>不能</b>放进 graph 的 {@code OverAllState}——graph 在执行节点前会对 state 做
     * 序列化快照，而 {@code ReactAgent} 实例与 {@code Message} 对话历史都不适合被序列化。
     * 因此让 state 只承载纯数据（round / defect_count，供条件边与日志使用），业务对象放在
     * 这个由节点闭包捕获的持有者里共享，与 {@code Orchestrator.Ctx} 是同一套规避手法。
     */
    private static final class GroupCtx {
        /** 本档全部方向，下标即 direction_index */
        final List<QuestionDirection> dirs;
        /** 当前草稿（重出会覆盖对应下标），审题与定稿都基于它 */
        final Map<Integer, PlannedQuestion> drafts = new HashMap<>();
        /** 定稿结果，对外返回 */
        final Map<Integer, PlannedQuestion> result = new HashMap<>();
        /** 本轮审题产出的缺陷（规则层 + Critic 合并） */
        final List<QuestionDefect> defects = new ArrayList<>();
        /**
         * 出题 Agent 的对话历史，跨轮累积。这是"纵向通信"通道——让出题 Agent 在重出时
         * 能看到自己上一轮的产出与收到的审核意见，而非凭空重写。
         */
        final List<Message> history = new ArrayList<>();
        /** 复用的出题 Agent 实例（必须复用，否则新实例看不到 history 之外的内部状态） */
        ReactAgent assembler;
        /** 已完成的出题轮次 */
        int round = 0;

        GroupCtx(List<QuestionDirection> dirs) {
            this.dirs = dirs;
        }
    }

    /**
     * 动态难度调节算法（与 Go 版本完全一致）
     * - 连续答对 ≥ 2 题 → 提高难度
     * - 连续答错 ≥ 2 题 → 降低难度
     * - 否则保持当前难度
     */
    public String adjustDifficulty(InterviewState state) {
        if (state.getConsecutiveRight() >= 2) {
            return switch (state.getCurrentDifficulty()) {
                case "easy" -> "medium";
                case "medium" -> "hard";
                default -> "hard";
            };
        }

        if (state.getConsecutiveWrong() >= 2) {
            return switch (state.getCurrentDifficulty()) {
                case "hard" -> "medium";
                case "medium" -> "easy";
                default -> "easy";
            };
        }

        return state.getCurrentDifficulty();
    }
}
