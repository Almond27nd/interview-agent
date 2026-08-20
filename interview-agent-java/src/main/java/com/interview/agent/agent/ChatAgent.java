/**
 */
package com.interview.agent.agent;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAgent {

    private final ChatModel chatModel;

    /**
     * 短期记忆中原始消息保留上限：超过这个数量就触发一次压缩，把最老的一批消息折叠进摘要，
     * 而不是简单截断丢弃（与 Go 版本"硬截断最近20条"的朴素实现不同）。
     */
    private static final int MAX_RAW_MESSAGES = 20; // 20条=10轮对话

    /** 每次触发压缩后，保留最近多少条原始消息（更早的折叠进摘要）。 */
    private static final int KEEP_RECENT_MESSAGES = 10; // 10条=5轮对话

    private static final String CHAT_AGENT_PROMPT = """
            你是 InterviewAgent 系统的智能助手，一个专注于技术面试的 AI 伙伴。

            你的能力范围：
            1. 回答技术面试相关的问题（面试技巧、知识点讲解、简历建议等）
            2. 帮助用户了解本系统的功能（模拟面试、评估报告、复习计划等）
            3. 日常技术问题的闲聊和答疑

            你的行为规范：
            - 友善专业，回答简洁有深度
            - 不要主动替用户做决定，保持引导式对话

            【重要：面试引导规则】
            当用户表达出想要面试的意图时（比如"开始面试"、"模拟面试"、"我想练习面试"、"怎么开始"、"怎么用"等），你必须引导用户点击页面底部的「开始面试」按钮。回复示例：

            "请点击页面底部的 **「开始面试」** 按钮来启动标准面试流程。点击后你可以：
            - 上传或粘贴 **岗位 JD**（支持链接、文件、文本）
            - 上传或粘贴你的 **简历**

            系统会自动完成 JD 分析、简历匹配度评估、智能出题、实时评分，最后生成完整的评估报告和个性化复习计划。"

            不要在聊天中直接启动面试流程，因为只有通过按钮才能进入包含 JD 分析、简历匹配、RAG 出题等完整环节的标准化面试。

            当前对话上下文中可能包含用户之前面试的历史信息，可以据此提供更个性化的建议。""";

    private static final String SUMMARIZE_PROMPT = """
            请把下面的多轮对话折叠成一段简洁的摘要，用于后续对话的上下文记忆。
            要求：
            1. 保留关键事实信息（用户提到的岗位/技术方向、已澄清过的背景、系统给出的关键结论等）
            2. 不超过 150 字，用陈述句表达，不要分点、不要输出多余说明
            3. 如果已有历史摘要，请在其基础上融合新内容，不要丢信息、不要重复

            已有摘要：
            %s

            新增对话内容：
            %s

            请输出融合后的完整摘要（纯文本）：""";

    /**
     * 聊天：维护短期记忆（近期原始消息 + 更早消息的滚动摘要），而非硬截断丢弃。
     * <p>
     * 本方法内部负责把本轮 (userInput, reply) 追加进 memory，并在超出阈值时触发摘要压缩；
     * 调用方只需持有同一个 {@link ChatMemory} 实例反复传入即可，不需要自己管理历史的增删。
     */
    public String chat(ChatMemory memory, String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(CHAT_AGENT_PROMPT));

        if (memory.getSummary() != null && !memory.getSummary().isEmpty()) {
            messages.add(new SystemMessage("以下是更早对话的摘要，供你了解背景（不代表用户当前意图）：\n" + memory.getSummary()));
        }
        messages.addAll(memory.getHistory());
        messages.add(new UserMessage(userInput));

        ChatResponse response = chatModel.call(new Prompt(messages));
        String reply = response.getResult().getOutput().getText();

        memory.getHistory().add(new UserMessage(userInput));
        memory.getHistory().add(new AssistantMessage(reply));
        compressIfNeeded(memory);

        return reply;
    }

    /**
     * 短期记忆超过阈值时，把最老的一批消息折叠进摘要（旧摘要 + 待折叠消息 → 一次 LLM 调用 → 新摘要），
     * 只保留最近 {@link #KEEP_RECENT_MESSAGES} 条原始消息，类似 LangChain 的
     * {@code ConversationSummaryBufferMemory}，而不是简单截断丢弃早期上下文。
     * 摘要调用失败时降级为直接截断（保证 history 不会无限增长，不影响主流程）。
     */
    private void compressIfNeeded(ChatMemory memory) {
        List<Message> history = memory.getHistory();
        if (history.size() <= MAX_RAW_MESSAGES) {
            return;
        }

        int foldCount = history.size() - KEEP_RECENT_MESSAGES;
        List<Message> toFold = new ArrayList<>(history.subList(0, foldCount));

        try {
            StringBuilder toFoldText = new StringBuilder();
            for (Message m : toFold) {
                String role = (m instanceof UserMessage) ? "用户" : "助手";
                toFoldText.append(role).append("：").append(m.getText()).append("\n");
            }

            String prevSummary = (memory.getSummary() == null || memory.getSummary().isEmpty())
                    ? "（无）" : memory.getSummary();

            String prompt = String.format(SUMMARIZE_PROMPT, prevSummary, toFoldText);
            ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
            memory.setSummary(response.getResult().getOutput().getText().trim());
        } catch (Exception e) {
            log.warn("[ChatAgent] 短期记忆摘要压缩失败，本次降级为直接截断丢弃: {}", e.getMessage());
        }

        List<Message> remaining = new ArrayList<>(history.subList(foldCount, history.size()));
        history.clear();
        history.addAll(remaining);
    }
}
