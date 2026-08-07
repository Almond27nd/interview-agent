/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

/**
 * LLM 调用 / 结构化输出解析在重试耗尽后仍失败时抛出的异常。
 * <p>
 * 与其他 RuntimeException 区分开，是为了让上层（WebSocketHandler）能够识别出
 * "这是一次已经重试过、大概率是瞬时故障（网络抖动 / LLM 偶发格式错误 / 限流等）导致的失败"，
 * 从而给用户一个更友好、更明确的提示（如"请重新点击开始面试再试一次"），
 * 而不是笼统地把所有异常都当成不可恢复的程序错误抛给用户。
 */
public class AgentCallException extends RuntimeException {
    public AgentCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
