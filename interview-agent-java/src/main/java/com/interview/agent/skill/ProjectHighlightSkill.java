/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.skill;

import com.interview.agent.agent.AgentUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

/**
 * 项目亮点提炼技能（与 Go 版本一致）
 * - 三阶段：start → collecting → mock_interview
 * - STAR 法则重构 + 亮点提炼 + 模拟追问
 */
@Slf4j
public class ProjectHighlightSkill implements Skill {

    private static final List<String> PROJECT_TRIGGERS = List.of(
            "项目亮点", "star", "怎么讲项目", "面试话术", "项目包装",
            "项目经历", "怎么介绍项目", "项目描述", "项目表达"
    );

    private final ChatModel chatModel;

    public ProjectHighlightSkill(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String name() { return "project_highlight"; }

    @Override
    public String description() { return "项目亮点提炼：STAR 法则重构 + 模拟面试追问"; }

    @Override
    public boolean match(String input) {
        return SkillUtils.containsAny(input, PROJECT_TRIGGERS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillResponse handle(String input, SkillState state) {
        if (SkillUtils.isQuitCommand(input)) {
            return SkillResponse.builder().content("已退出项目亮点模式。").done(true).state(state).build();
        }
        if (state == null) state = SkillState.create(name());
        String phase = (String) state.getData().getOrDefault("phase", "start");

        return switch (phase) {
            case "start" -> handleStart(input, state);
            case "collecting" -> handleCollecting(input, state);
            case "mock_interview" -> handleMockInterview(input, state);
            default -> handleStart(input, state);
        };
    }

    private SkillResponse handleStart(String input, SkillState state) {
        state.getData().put("phase", "collecting");
        return SkillResponse.builder()
                .content("🌟 **项目亮点提炼**\n\n请描述你的项目经历，包括：\n1. 项目背景（做什么的）\n2. 你的角色和职责\n3. 遇到的技术挑战\n4. 你的解决方案\n5. 最终成果（最好有数据）")
                .done(false)
                .nextPrompt("请描述你的项目：")
                .state(state)
                .build();
    }

    private SkillResponse handleCollecting(String input, SkillState state) {
        state.getData().put("project_description", input);
        state.getData().put("phase", "mock_interview");
        state.getData().put("mock_round", 0);

        // 用 STAR 法则分析项目
        String analysis = analyzeProject(input);
        state.getData().put("analysis", analysis);

        // 生成模拟追问
        String mockQuestion = generateMockQuestion(input, 0);
        state.getData().put("current_mock_question", mockQuestion);

        String content = String.format("""
                %s

                ---

                接下来模拟面试官追问环节（共 3 轮）：

                **面试官追问 1：** %s""", analysis, mockQuestion);

        return SkillResponse.builder()
                .content(content)
                .done(false)
                .nextPrompt("请回答面试官的追问：")
                .state(state)
                .build();
    }

    @SuppressWarnings("unchecked")
    private SkillResponse handleMockInterview(String input, SkillState state) {
        int mockRound = ((Number) state.getData().getOrDefault("mock_round", 0)).intValue();
        String projectDesc = (String) state.getData().get("project_description");

        // 评估用户回答
        String evaluation = evaluateMockAnswer(
                (String) state.getData().get("current_mock_question"),
                input, projectDesc);

        mockRound++;
        state.getData().put("mock_round", mockRound);
        state.nextRound();

        if (mockRound >= 3) {
            String content = String.format("""
                    %s

                    ---

                    ✅ 模拟追问完成！你已经对这个项目的面试表达有了更好的准备。
                    建议多练习几次，直到回答自然流畅。""", evaluation);

            return SkillResponse.builder().content(content).done(true).state(state).build();
        }

        String nextQuestion = generateMockQuestion(projectDesc, mockRound);
        state.getData().put("current_mock_question", nextQuestion);

        String content = String.format("%s\n\n**面试官追问 %d：** %s",
                evaluation, mockRound + 1, nextQuestion);

        return SkillResponse.builder()
                .content(content)
                .done(false)
                .nextPrompt("请回答面试官的追问：")
                .state(state)
                .build();
    }

    private String analyzeProject(String projectDesc) {
        String prompt = String.format("""
                请用 STAR 法则分析以下项目经历，并提炼亮点：

                项目描述：%s

                请输出：
                1. **STAR 重构**（Situation / Task / Action / Result 四段式）
                2. **核心亮点**（3 个最值得强调的技术亮点）
                3. **面试话术建议**（如何用 1-2 分钟简洁介绍这个项目）
                4. **预测追问方向**（面试官可能会深入追问哪些点）""", projectDesc);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            return "项目分析生成失败，请重试。";
        }
    }

    private String generateMockQuestion(String projectDesc, int round) {
        String prompt = String.format("""
                你是技术面试官。基于以下项目描述，生成第 %d 个追问问题。

                项目描述：%s

                要求：
                - 第 1 个追问偏技术细节（如架构设计、性能优化）
                - 第 2 个追问偏挑战和解决方案（如遇到的坑、trade-off）
                - 第 3 个追问偏反思和成长（如复盘、改进）

                只输出一个追问问题，不要输出其他内容。""", round + 1, projectDesc);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return response.getResult().getOutput().getText().trim();
        } catch (Exception e) {
            return "请详细描述一下这个项目的技术架构？";
        }
    }

    private String evaluateMockAnswer(String question, String answer, String projectContext) {
        String prompt = String.format("""
                你是技术面试官。请评价候选人对以下追问的回答：

                追问：%s
                候选人回答：%s
                项目背景：%s

                请给出：
                1. 回答评分（优秀/良好/一般/需改进）
                2. 优点
                3. 改进建议
                4. 推荐话术（如何更好地回答这个问题）""", question, answer, projectContext);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            return "评价生成失败，请重试。";
        }
    }
}
