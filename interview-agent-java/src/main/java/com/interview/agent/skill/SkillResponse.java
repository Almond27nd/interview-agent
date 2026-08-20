/**
 */
package com.interview.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 单轮交互的响应（与 Go 版本一致）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillResponse {
    private String content;     // 回复内容
    private boolean done;       // 该 Skill 交互是否结束
    private String nextPrompt;  // 引导用户下一步输入的提示语
    private SkillState state;   // 更新后的状态
}
