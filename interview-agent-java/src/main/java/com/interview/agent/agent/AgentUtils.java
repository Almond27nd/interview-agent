/**
 */
package com.interview.agent.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Agent 通用工具方法
 */
@Slf4j
public class AgentUtils {

    /** 默认最大尝试次数（含首次），覆盖大多数瞬时性失败（网络抖动、LLM 偶发输出格式错误、限流等） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * 从 LLM 响应文本中提取 JSON 内容。
     * 处理 markdown 代码块包裹的情况，提取第一个 { 到最后一个 } 之间的内容。
     * 与 Go 版本 extractJSON() 逻辑完全一致。
     */
    public static String extractJSON(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 找到第一个 {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                start = i;
                break;
            }
        }
        if (start == -1) {
            return text;
        }

        // 找到最后一个 }
        int end = -1;
        for (int i = text.length() - 1; i >= start; i--) {
            if (text.charAt(i) == '}') {
                end = i + 1;
                break;
            }
        }
        if (end == -1) {
            return text;
        }

        return text.substring(start, end);
    }

    /**
     * 带重试的调用封装：用于"调用 LLM + 解析结构化输出"这类环节。
     * <p>
     * 背景：整个面试流程有 6~7 个 LLM 调用节点（JD 分析、简历匹配、出题规划两阶段、
     * 面试提问/评分/追问、评估报告、复习计划），链路很长，任意一个节点因为网络抖动、
     * LLM 偶发返回了不完整/不合规的 JSON、限流等"瞬时性"原因失败，都会导致耗时几分钟的
     * 整个流程从头报错、用户前面的输入和等待全部作废。这类失败往往"再调一次就好了"，
     * 因此在框架层做统一的自动重试，而不需要每个 Agent 各自实现一遍重试逻辑。
     * <p>
     * 重试仅覆盖调用本身（LLM 请求 + JSON 解析），不涉及用户输入内容是否合理——
     * 那属于"用户可修复错误"，应该在更早的入口处（如 WebSocketHandler 校验 JD/简历）
     * 直接拦截，而不是靠重试掩盖。
     *
     * @param stepName    步骤名（用于日志与最终异常信息，如 "JD 分析"）
     * @param maxAttempts 最大尝试次数（含首次），建议使用 {@link #DEFAULT_MAX_ATTEMPTS}
     * @param action      实际调用逻辑（LLM 请求 + 解析），失败请直接抛异常
     * @return action 的返回值
     * @throws AgentCallException 重试耗尽后仍失败时抛出，包裹最后一次失败的原始异常
     */
    public static <T> T callWithRetry(String stepName, int maxAttempts, Supplier<T> action) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("[{}] 第 {}/{} 次尝试失败，准备重试: {}", stepName, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("[{}] 第 {}/{} 次尝试失败，重试耗尽: {}", stepName, attempt, maxAttempts, e.getMessage());
                }
            }
        }
        throw new AgentCallException(
                String.format("%s 失败（已重试 %d 次），请稍后重试", stepName, maxAttempts), lastError);
    }
}
