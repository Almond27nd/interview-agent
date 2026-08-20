/**
 */
package com.interview.agent.mcp;

import com.interview.agent.memory.LongTermMemory;
import com.interview.agent.memory.UserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 候选人画像查询工具（B3）：把 {@link LongTermMemory#getWeakPoints} 包装成 Tool，
 * 让 ReviewPlanner 在生成复习计划时可以"按需回查"——判断某个薄弱点是长期反复出现，
 * 还是本场偶发——而不是完全依赖 Orchestrator 提前拼好的静态文本上下文。
 * 这样 Prompt 可以更短，模型只在真正需要细节时才调用。
 */
@Component
@RequiredArgsConstructor
public class CandidateProfileTool {

    private final LongTermMemory longTermMemory;

    public ToolCallback asToolCallback(String userId) {
        return FunctionToolCallback
                .builder("query_candidate_weak_point_history", (NoArgs req) -> {
                    List<UserProfile.WeakPoint> weakPoints = longTermMemory.getWeakPoints(userId);
                    if (weakPoints == null || weakPoints.isEmpty()) {
                        return "该候选人暂无跨场次的历史薄弱点记录（可能是第一次面试，或近期薄弱点已全部改善）。";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (UserProfile.WeakPoint wp : weakPoints) {
                        sb.append(String.format("- %s：历史得分 %.0f，被考察 %d 次，答错 %d 次%n",
                                wp.getTopic(), wp.getScore(), wp.getHitCount(), wp.getWrongCount()));
                    }
                    return sb.toString();
                })
                .description("查询该候选人跨历次面试的薄弱点统计（主题、历史得分、被考察次数、答错次数），"
                        + "无需参数。当需要判断本场报告里某个薄弱点是长期反复出现（应重点安排学习计划）"
                        + "还是本场偶发失误（可以适当降低优先级）时调用。")
                .inputType(NoArgs.class)
                .build();
    }

    /** 该工具无需参数，用一个空 record 承载（保持与其他 FunctionToolCallback 一致的构建方式） */
    public record NoArgs() {}
}
