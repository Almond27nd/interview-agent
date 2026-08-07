/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.skill;

import java.util.List;

/**
 * Skill 工具方法（与 Go 版本一致）
 */
public class SkillUtils {

    /** 判断用户输入是否是退出命令 */
    public static boolean isQuitCommand(String input) {
        String lower = input.trim().toLowerCase();
        return lower.equals("/quit") || lower.equals("/exit")
                || lower.equals("退出") || lower.equals("结束");
    }

    /** 检查文本是否包含关键词列表中的任意一个 */
    public static boolean containsAny(String text, List<String> keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
