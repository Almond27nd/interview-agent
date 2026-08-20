/**
 */
package com.interview.agent.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 闲聊场景的短期记忆容器：近期原始消息（history）+ 更早消息折叠后的滚动摘要（summary）。
 * <p>
 * 之前的实现是"硬截断"：只取最近 N 条消息拼进 Prompt，超出窗口的早期上下文直接丢弃。
 * 这里改为类似 LangChain {@code ConversationSummaryBufferMemory} 的思路：早期消息不是被
 * 丢弃，而是被 LLM 折叠成一段摘要保留下来，既控制了 Prompt 长度，又不完全损失早期上下文
 * （比如用户很早之前提到的岗位方向、已澄清过的背景信息）。
 * <p>
 * 增删逻辑由 {@link ChatAgent} 内部维护，调用方（如 WebSocketHandler.WSSession）只需要
 * 持有同一个实例并反复传给 {@link ChatAgent#chat}，不需要自己操心何时截断/摘要。
 */
@Getter
@Setter
public class ChatMemory {

    /** 近期原始消息，保持原文不压缩，供模型看到最新的上下文细节。 */
    private final List<Message> history = new ArrayList<>();

    /** 更早消息折叠后的滚动摘要；无历史时为空字符串。 */
    private String summary = "";
}
