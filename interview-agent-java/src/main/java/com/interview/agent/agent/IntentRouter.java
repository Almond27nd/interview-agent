/**
 */
package com.interview.agent.agent;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图路由器（关键词匹配，非 LLM），与 Go 版本逻辑完全一致。
 */
@Component
public class IntentRouter {

    public static final String INTENT_START_INTERVIEW = "start_interview";
    public static final String INTENT_UPLOAD_RESUME = "upload_resume";
    public static final String INTENT_UPLOAD_JD = "upload_jd";
    public static final String INTENT_VIEW_HISTORY = "view_history";
    public static final String INTENT_SKILL = "skill";
    public static final String INTENT_CHAT = "chat";

    private static final List<String> INTERVIEW_TRIGGERS = List.of(
            "开始面试", "模拟面试", "开始模拟", "面试一下",
            "start interview", "mock interview",
            "我要面试", "开始吧", "来面试"
    );

    private static final List<String> RESUME_TRIGGERS = List.of(
            "简历", "resume", "我的简历"
    );

    private static final List<String> JD_TRIGGERS = List.of(
            "jd", "JD", "岗位", "职位", "招聘"
    );

    private static final List<String> HISTORY_TRIGGERS = List.of(
            "历史", "记录", "上次面试", "面试记录", "history"
    );

    /**
     * 路由用户输入到对应意图（与 Go 版本路由优先级完全一致）
     */
    public String route(String input, boolean isInterviewing) {
        // 面试进行中：所有输入都走面试流程
        if (isInterviewing) {
            return INTENT_START_INTERVIEW;
        }

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();

        // 检查是否是文件路径
        if (isFilePath(trimmed)) {
            if (containsAny(lower, RESUME_TRIGGERS)) {
                return INTENT_UPLOAD_RESUME;
            }
            return INTENT_UPLOAD_RESUME; // 文件默认当简历处理
        }

        // 检查是否是 URL（JD 链接）
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return INTENT_UPLOAD_JD;
        }

        // 关键词匹配
        if (containsAny(lower, INTERVIEW_TRIGGERS)) {
            return INTENT_START_INTERVIEW;
        }
        if (containsAny(lower, HISTORY_TRIGGERS)) {
            return INTENT_VIEW_HISTORY;
        }

        // 默认走聊天
        return INTENT_CHAT;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isFilePath(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.startsWith("/") || s.startsWith("./") || s.startsWith("~/")) {
            return true;
        }
        String lower = s.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }
}
