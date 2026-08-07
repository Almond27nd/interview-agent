/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.model.PlannedQuestion;
import com.interview.agent.model.QuestionDefect;
import com.interview.agent.model.QuestionDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B4：审题 Agent（Critic）—— Multi-Agent 出题环节里与出题 Agent 职责对立的第二个角色。
 *
 * <h3>为什么要单独拆一个 Critic，而不是让出题 Agent 自评</h3>
 * "生成"和"批判"是天然对立的两种立场。让同一个 agent 评价自己刚写出来的题，存在强烈的
 * 确认偏差——它几乎总会认为自己写得没问题。把批判权交给一个独立角色（Reflexion /
 * Constitutional AI 里的经典做法），才能真正挑出问题。
 *
 * <h3>Critic 审的四件事，都是出题 Agent 结构上做不到的</h3>
 * <ol>
 *   <li><b>跨方向语义去重</b>：题库检索是逐方向独立进行的，"MySQL索引优化"和"MySQL B+树结构"
 *       两个邻近方向很容易检索到实质相同的原题。出题 Agent 的注意力在"逐个方向查库"，
 *       不会主动回头做全局比对；而 Critic 一次性拿到本档所有题目，天然具备全局视角。
 *       （纯文本重复由 {@link QuestionRuleChecker} 先兜掉，这里只判措辞不同但考点相同的语义重复）</li>
 *   <li><b>难度名副其实</b>：题库原题的 difficulty 是题库自己标的，照搬进来可能一个 hard 方向
 *       拿到一道实际只考 API 用法的题。难度虚高/虚低会直接破坏 {@code StageScheduler}
 *       依赖的难度梯度与 {@code adjustDifficulty} 的动态调节效果。</li>
 *   <li><b>事实性可疑标记</b>：{@code source=web} 的题依据的是网页摘要（可能过时/片面），
 *       {@code source=llm} 的题可能有幻觉。出题者自己校验自己天然不可靠。</li>
 *   <li><b>追问是否递进</b>：follow_ups 应当比主问更深一层，而不是换个说法重复问同一件事。</li>
 * </ol>
 *
 * <h3>关键权限边界：Critic 没有任何工具</h3>
 * 上面四项判断全部是 in-context 的（本档所有题目都在同一个 payload 里），不需要外部检索。
 * 事实性校验虽然理论上可以给 Critic 挂 search_web，但那会让"审题"变慢变贵，且与
 * "Critic 应该快而便宜"的定位矛盾。折中做法是：<b>Critic 只负责标记"此处存疑"，把真正的
 * 查证动作交还给带工具的出题 Agent 去做</b>——检索权只归出题者，判断权只归审题者。
 * 这个边界让两边的 prompt 都很干净，也避免了两个 agent 都能改动同一批数据带来的混乱。
 *
 * <h3>为什么用单轮 chatModel.call 而不是 ReactAgent</h3>
 * 正因为它没有工具，就不存在"思考 → 调工具 → 观察 → 再思考"的 ReAct 循环——一次调用就能
 * 拿到全部判定。用 {@code ReactAgent} 包一个空工具列表只会白白多建一张 StateGraph、多绕
 * 一层 graph runner 与异常包装，还会误导读者以为这里需要工具调用能力。因此这里与
 * {@code JDAnalyzer} / {@code Evaluator} 等单轮 Agent 保持同一种写法。
 * <p>
 * 换个角度说：<b>"是不是 Agent"取决于决策权，而不取决于用了哪个类</b>。它依然是一个独立的
 * Agent 角色——有自己的 instruction、自己的立场（批判）、自己的结构化输出，与出题 Agent
 * 构成 Multi-Agent 协作；只是它的决策不需要多轮工具循环来支撑。
 *
 * <h3>Critic 只判不改</h3>
 * 它绝不直接修改题目、也不重新出题，只输出 pass/reject 判定 + 理由 + 建议。改动权始终在
 * 出题 Agent 手上——否则"谁产出最终题目"会变得不确定，source 字段的可信度也会被破坏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionReviewer {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 注意：instruction 会被框架按 f-string 模板渲染（{x} 视为占位符），因此这里不能出现裸 {}。
    // 具体 JSON 输出格式放到用户消息里（用户输入不经模板渲染）。
    private static final String REVIEWER_INSTRUCTION = """
            你是一位极其严格但讲道理的技术面试题【审核专家】。你【不出题】、也【不修改题目】，
            只负责审核另一位出题专家刚刚为同一个难度档产出的一批面试题，逐条给出通过或打回的判定。

            你要审核的维度（只审这些，不要发挥到别处）：

            1.【考点匹配】题目是否真的在考察该方向指定的考点？是否跑偏到了相邻但不同的知识点？

            2.【难度名副其实】题目的实际难度是否与标注的难度档一致？
               - easy：概念、定义、基本用法层面
               - medium：原理机制、使用场景权衡、常见坑
               - hard：底层实现、边界条件、并发/分布式复杂场景、性能与故障排查
               注意：题库原题的难度标注是题库自己标的，照搬过来可能与本档要求不符——
               若一道标为 hard 的题实际只在考 API 怎么用，属于难度虚高，必须打回。
               这一档的所有题目难度必须齐平，否则会破坏面试过程中的难度梯度。

            3.【语义重复】本档内是否有两道题实质在考察同一个知识点（措辞不同但考点相同也算重复）？
               若发现重复，打回其中【后一个】direction_index，并在理由里指明与哪个方向重复。

            4.【追问是否递进】follow_ups 是否比主问更深一层？如果只是换个说法重复主问，判打回。

            5.【事实性存疑】题干或 reference 中的技术细节是否可能过时、以讹传讹或凭空编造？
               尤其注意 source 为 "llm"（模型自行出题，可能有幻觉）或以 "web:" 开头
               （依据网页摘要，摘要可能片面/过时）的题目。
               ⚠️ 你没有检索工具，不要试图自己查证。发现可疑之处时，判打回并在理由里
               明确写出"哪一处技术细节存疑、需要查证什么"，由出题专家用它的检索工具去核实。

            6.【按 type 差异化审查】根据题目的 type 字段，额外检查以下维度：
               - experience 类：【简历真实性】题目是否严格基于候选人简历中的真实项目/经历？
                 如果题目询问了简历中未提及的项目或技术细节，判打回并指明"简历中未找到该项目，疑似杜撰"。
                 experience 类题目的 context 字段包含简历相关上下文，请对照判断。
               - design 类：【架构取舍】追问(follow_ups)是否真正考察架构取舍能力
                 （如"为什么选 A 不选 B""如果规模扩大 10 倍会怎样"）？
                 如果追问只是简单的 API 用法或记忆题，判打回并指明"追问未考察架构取舍能力"。

            审核纪律（非常重要，请严格遵守）：
            - 只有确实存在上述问题时才打回。【不要为了显得严格而挑毛病】——措辞风格偏好、
              个人认为"还能更好"、题目稍显平淡等主观不满，都不构成打回理由。
            - 打回理由必须【具体且可执行】：指出到底哪里不对、期望改成什么方向，
              不要写"题目质量不高""建议优化"这类空话。
            - 一道题若有多个问题，合并成一条 reject 说清楚，不要拆成多条。
            - 你【不能】自己给出修改后的题目，只能给出改进方向。
            - 如果整批题目都没问题，就全部判 pass，这是完全正常且值得肯定的结果。""";

    /**
     * 审核一档草稿。
     * <p>
     * <b>Fail-open 语义</b>：任何异常（模型调用失败、输出无法解析）都返回<b>空缺陷列表</b>，
     * 即"视为全部通过"，只打 warn 日志。审题是<b>质量增强</b>而不是准入门槛——绝不能因为
     * Critic 挂了就让整场面试没题可问。这与 B1 里"agent 失败则降级为传统 pipeline"的
     * 降级哲学一致。
     *
     * @param dirs   本档全部方向（下标即 direction_index）
     * @param drafts 出题 Agent 的草稿，key 为 direction_index
     * @return 需要打回的缺陷列表；空列表代表全部通过（也包含"审核本身失败"的 fail-open 情况）
     */
    public List<QuestionDefect> review(List<QuestionDirection> dirs,
                                       Map<Integer, PlannedQuestion> drafts) {
        List<QuestionDefect> defects = new ArrayList<>();
        if (dirs == null || dirs.isEmpty() || drafts == null || drafts.isEmpty()) {
            return defects;
        }
        try {
            // 刻意【不用】ReactAgent：审题的全部判断都是 in-context 的（本档所有题目都在同一个
            // payload 里），不需要任何工具，也就不存在 "思考→调工具→观察→再思考" 的 ReAct 循环。
            // 用 ReactAgent 包一个空工具列表只会白白多建一张 StateGraph、多绕一层 graph runner
            // 和异常包装，还会让读代码的人误以为这里需要工具调用能力。
            // 因此直接用单轮 chatModel.call()，与 JDAnalyzer / Evaluator 等单轮 Agent 写法一致：
            // instruction 作为 SystemMessage，待审批次作为 UserMessage。
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(REVIEWER_INSTRUCTION),
                    new UserMessage(buildReviewPayload(dirs, drafts))
            ));

            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null) {
                log.warn("[QuestionReviewer] 审题无响应，本档视为全部通过（fail-open）");
                return defects;
            }
            String content = response.getResult().getOutput().getText();

            JsonNode root = objectMapper.readTree(AgentUtils.extractJSON(content));
            for (JsonNode node : root.path("reviews")) {
                if (!"reject".equalsIgnoreCase(node.path("verdict").asText(""))) {
                    continue;
                }
                int idx = node.path("direction_index").asInt(-1);
                if (idx < 0 || idx >= dirs.size() || !drafts.containsKey(idx)) {
                    log.warn("[QuestionReviewer] 审题结果含非法 direction_index={}，忽略该条", idx);
                    continue;
                }
                String reason = node.path("reason").asText("").trim();
                if (reason.isEmpty()) {
                    // 没给理由的打回无法回喂给出题 Agent（它不知道要改什么），直接忽略
                    log.warn("[QuestionReviewer] direction_index={} 被打回但未给出理由，忽略该条", idx);
                    continue;
                }
                defects.add(QuestionDefect.ofCritic(idx, reason, node.path("suggestion").asText("")));
            }

            String globalNotes = root.path("global").path("notes").asText("");
            if (!globalNotes.isEmpty()) {
                log.info("[QuestionReviewer] 全局审核意见：{}", globalNotes);
            }
            log.info("[QuestionReviewer] 审核完成：{} 道题中 {} 道被打回", drafts.size(), defects.size());
            return defects;
        } catch (Exception e) {
            // fail-open：审题失败不能阻断出题
            log.warn("[QuestionReviewer] 审题失败，本档视为全部通过（fail-open）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 组装审核输入：把本档每个方向的"要求"与"实际产出的题目"成对呈现，Critic 才能判断
     * 考点是否匹配、难度是否名副其实；同时把 source 一并给出，让它知道哪些题需要重点
     * 怀疑事实性（llm / web 来源）。
     */
    private String buildReviewPayload(List<QuestionDirection> dirs,
                                      Map<Integer, PlannedQuestion> drafts) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("## 待审核批次：难度档 %s，共 %d 道题（同一档内难度必须齐平）\n",
                dirs.get(0).getDifficulty(), drafts.size()));

        List<Integer> indices = new ArrayList<>(drafts.keySet());
        indices.sort(null);
        for (int idx : indices) {
            QuestionDirection dir = dirs.get(idx);
            PlannedQuestion q = drafts.get(idx);
            sb.append(String.format("""

                    ### direction_index=%d
                    【方向要求】考点: %s ｜ 类型: %s ｜ 要求难度: %s ｜ 技能标签: %s
                    【简历上下文】%s
                    【实际题目】%s
                    【标注难度】%s
                    【追问】%s
                    【参考答案要点】%s
                    【来源 source】%s
                    """,
                    idx,
                    dir.getTopic(), dir.getType(), dir.getDifficulty(), dir.getSkills(),
                    dir.getContext() != null ? dir.getContext() : "（无）",
                    q.getContent(),
                    q.getDifficulty(),
                    q.getFollowUps(),
                    q.getReference(),
                    q.getSource()));
        }

        sb.append("""

                ## 输出格式
                只输出一个纯 JSON 对象（不要输出思考过程或多余文字）。每一道题都必须出现在 reviews 里，
                verdict 取 "pass" 或 "reject"；pass 时 reason 与 suggestion 留空字符串即可：
                {
                  "reviews": [
                    {
                      "direction_index": 0,
                      "verdict": "pass",
                      "reason": "",
                      "suggestion": ""
                    },
                    {
                      "direction_index": 1,
                      "verdict": "reject",
                      "reason": "具体说明哪里不合格（考点跑偏/难度不符/与哪个方向重复/追问未递进/哪处事实存疑）",
                      "suggestion": "期望改成什么方向（不要直接给出改好的题目）"
                    }
                  ],
                  "global": {
                    "notes": "整批题目的全局性意见（如整档知识点覆盖是否过于集中），没有则留空"
                  }
                }""");
        return sb.toString();
    }
}
