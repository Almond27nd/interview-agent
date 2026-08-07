/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.skill;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 注册中心（与 Go 版本一致）
 * 管理所有已注册的 Skill 并提供意图匹配，先注册的优先级高
 */
@Component
public class SkillRegistry {

    private final List<Skill> skills = new ArrayList<>();

    /** 注册一个 Skill（先注册的优先级高） */
    public void register(Skill skill) {
        skills.add(skill);
    }

    /** 根据用户输入匹配合适的 Skill */
    public Skill match(String input) {
        for (Skill s : skills) {
            if (s.match(input)) {
                return s;
            }
        }
        return null;
    }

    /** 返回所有已注册的 Skill */
    public List<Skill> list() {
        return skills;
    }
}
