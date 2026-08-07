/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Skill 的交互状态（与 Go 版本一致）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillState {

    private String skillName;
    private String userId;
    private int round;
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();
    private LocalDateTime startedAt;

    public static SkillState create(String skillName) {
        return SkillState.builder()
                .skillName(skillName)
                .round(1)
                .data(new HashMap<>())
                .startedAt(LocalDateTime.now())
                .build();
    }

    public void nextRound() {
        this.round++;
    }

    /** 检查 Skill 会话是否已过期（30 分钟） */
    public boolean isExpired() {
        return Duration.between(startedAt, LocalDateTime.now()).toMinutes() > 30;
    }
}
