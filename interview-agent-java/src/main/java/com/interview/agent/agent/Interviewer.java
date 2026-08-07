/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.model.AnswerScore;
import com.interview.agent.model.InterviewState;
import com.interview.agent.model.PlannedQuestion;
import com.interview.agent.model.QAPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class Interviewer {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INTERVIEWER_SYSTEM_PROMPT = """
            你是一位资深的技术面试官，风格专业但友善。你正在进行一场技术面试。

            面试规则：
            1. 每次只问一个问题，等候选人回答后再继续
            2. 根据候选人回答质量决定是否追问
            3. 对优秀的回答给予肯定，对不完整的回答进行引导
            4. 保持专业、友善的语气
            5. 不要直接告诉候选人答案

            当前面试上下文：
            - 岗位：%s
            - 当前第 %d/%d 题
            - 当前难度：%s
            %s""";

    private static final String SCORE_PROMPT = """
            请对候选人的回答进行客观评分和反馈。

            题目：%s
            候选人回答：%s
            参考答案要点：%s

            【核心原则】严格基于候选人实际回答的内容进行评分：
            - 只认定候选人明确表述出来的知识点，不要推测、脑补、或替候选人补充任何内容
            - 候选人没有提到的知识点，一律算作遗漏（key_points_missed）
            - 候选人说"不会"、"不知道"、"不太了解"、"跳过"等，得分应为 0-10 分
            - 候选人回答偏题或答非所问，得分应为 0-20 分
            - feedback 要指出候选人具体哪里答得好、哪里没有覆盖到，不要笼统夸奖

            请先逐条对照参考答案要点，列出候选人命中了哪些、遗漏了哪些，再根据命中比例和深度给出分数。

            请输出纯 JSON 格式：
            {
              "score": <0-100的数值，根据下方评分标准和实际命中比例计算>,
              "feedback": "具体指出哪些点答得好、哪些点遗漏了",
              "key_points_hit": ["候选人明确提到的知识点1", "知识点2"],
              "key_points_missed": ["候选人未提到的知识点1", "知识点2"],
              "should_follow_up": true
            }

            评分标准：
            - 90-100：完美回答，覆盖所有要点且有深度
            - 70-89：良好回答，覆盖主要要点
            - 50-69：基本回答，有明显遗漏
            - 30-49：较差回答，只覆盖少量要点
            - 0-29：未能回答或完全偏题""";

    private static final String UPDATE_PROFILE_PROMPT = """
            请基于以下信息更新候选人画像。要求：简洁、结构化、不超过200字。

            %s

            本轮新信息：
            - 第 %d 题，考察技能：%s
            - 得分：%.0f/100
            - 命中要点：%s
            - 遗漏要点：%s

            请输出更新后的完整画像（纯文本，不要 JSON）。画像应包含：
            1. 技能强项（哪些领域表现好）
            2. 薄弱领域（哪些方面需加强）
            3. 答题风格特征（如：偏理论/偏实践、善于举例/偏抽象等）""";

    /**
     * 面试官提问（流式）。onDelta 在 LLM 逐 token 生成过程中被多次回调（每次一个增量片段），
     * 用于前端打字机效果；方法本身仍保持阻塞语义，返回完整拼接后的文本，不改变
     * Orchestrator 侧"问完题再等用户回答"的同步流程。
     */
    public String askQuestion(InterviewState state, PlannedQuestion question, String position,
                               Consumer<String> onDelta) {
        // 构建候选人画像上下文
        String profileContext = "";
        if (state.getCandidateProfile() != null && !state.getCandidateProfile().isEmpty()) {
            profileContext = "\n候选人画像：\n" + state.getCandidateProfile();
        }

        String systemPrompt = String.format(INTERVIEWER_SYSTEM_PROMPT,
                position,
                state.getCurrentQuestion(),
                state.getTotalQuestions(),
                state.getCurrentDifficulty(),
                profileContext);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // 与 Go 版本一致：直接提出题库原题、保持简洁，不要让模型加铺垫或改写。
        // 也不再塞入历史问答（每题独立、干净地提出），避免模型被上下文诱导加承接语。
        // （之前的「可以适当改写使其更自然」会让模型加上「好的，我们进入下一个问题」「（稍作停顿）」
        //   等大量啰嗦的承接语和铺垫，既不像题库原题，也会让第一题看起来像在承接上一题。）
        String questionMsg = String.format(
                "请以面试官的身份直接提出以下面试题，保持简洁，不要加额外的铺垫、背景说明或解释：\n\n%s",
                question.getContent());
        messages.add(new UserMessage(questionMsg));

        // 提问失败（网络抖动等瞬时问题）会导致整场面试直接中断，代价远大于多试一次，因此这里也做重试。
        return AgentUtils.callWithRetry("面试官提问", AgentUtils.DEFAULT_MAX_ATTEMPTS, () ->
                streamAndCollect(new Prompt(messages), onDelta));
    }

    /**
     * 消费流式响应，每收到一个增量片段就回调一次 onDelta，同时拼接出完整文本作为返回值。
     * DashScope 默认 incrementalOutput=true：Flux 中每个 ChatResponse 的文本是"增量片段"而非累积全文，
     * 因此这里用 StringBuilder 逐块 append，不能直接取最后一个 chunk。
     */
    private String streamAndCollect(Prompt prompt, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        chatModel.stream(prompt)
                .doOnNext(resp -> {
                    if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                        return;
                    }
                    String delta = resp.getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }
                })
                .blockLast();
        if (full.length() == 0) {
            throw new RuntimeException("流式生成返回空内容");
        }
        return full.toString();
    }

    /**
     * 评分。重试耗尽后不抛异常、不中断面试，而是降级为默认分数 —— 因为此时候选人已经在等待
     * 下一题，让整场面试因为一次评分失败而崩掉，代价远大于给一个保守的默认分。
     */
    public AnswerScore scoreAnswer(PlannedQuestion question, String answer) {
        String reference = question.getReference() != null ? question.getReference() : "无参考答案";
        String userMsg = String.format(SCORE_PROMPT, question.getContent(), answer, reference);

        try {
            return AgentUtils.callWithRetry("答案评分", AgentUtils.DEFAULT_MAX_ATTEMPTS, () -> {
                Prompt prompt = new Prompt(List.of(
                        new SystemMessage("你是一个严格客观的技术面试评分专家。"),
                        new UserMessage(userMsg)
                ));

                ChatResponse response = chatModel.call(prompt);
                String content = response.getResult().getOutput().getText();
                String json = AgentUtils.extractJSON(content);

                try {
                    return objectMapper.readValue(json, AnswerScore.class);
                } catch (Exception e) {
                    throw new RuntimeException("评分结果解析失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            log.error("[Interviewer] 评分重试耗尽，降级为默认分数: {}", e.getMessage());
            return AnswerScore.builder()
                    .score(50)
                    .feedback("评分服务暂时异常，已给予默认分数，不影响后续答题")
                    .keyPointsHit(List.of())
                    .keyPointsMissed(List.of())
                    .shouldFollowUp(false)
                    .build();
        }
    }

    /**
     * 更新候选人动态画像
     */
    public String updateCandidateProfile(String currentProfile, int questionNum,
                                          PlannedQuestion question, AnswerScore score) {
        String prevProfile = (currentProfile != null && !currentProfile.isEmpty())
                ? "当前画像：\n" + currentProfile
                : "（尚无历史画像，这是第一题）";

        String skills = question.getSkills() != null ? String.join(", ", question.getSkills()) : "未知";
        String hit = score.getKeyPointsHit() != null ? String.join(", ", score.getKeyPointsHit()) : "无";
        String missed = score.getKeyPointsMissed() != null ? String.join(", ", score.getKeyPointsMissed()) : "无";

        String userMsg = String.format(UPDATE_PROFILE_PROMPT,
                prevProfile, questionNum, skills, score.getScore(), hit, missed);

        Prompt prompt = new Prompt(List.of(new UserMessage(userMsg)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    /**
     * 追问（流式，onDelta 用于打字机效果，语义与 {@link #askQuestion} 一致）
     */
    public String followUp(InterviewState state, PlannedQuestion question,
                           String answer, String feedback, List<String> missedPoints, String position,
                           Consumer<String> onDelta) {
        String systemPrompt = String.format("""
                你是一位资深的技术面试官，正在对候选人的回答进行追问。
                岗位：%s，当前第 %d/%d 题。

                原题：%s
                候选人回答：%s
                评分反馈：%s
                遗漏知识点：%s

                请针对候选人遗漏的知识点，提出一个引导性的追问，帮助候选人展示更多能力。
                要求：只输出追问内容，不要输出其他解释。""",
                position,
                state.getCurrentQuestion(),
                state.getTotalQuestions(),
                question.getContent(),
                answer,
                feedback,
                String.join(", ", missedPoints != null ? missedPoints : List.of()));

        Prompt prompt = new Prompt(List.of(new UserMessage(systemPrompt)));
        return AgentUtils.callWithRetry("追问", AgentUtils.DEFAULT_MAX_ATTEMPTS, () ->
                streamAndCollect(prompt, onDelta));
    }
}
