/**
 */
package com.interview.agent.graph;

import com.interview.agent.model.PlannedQuestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试阶段化取题 + 阶段内难度调节的纯调度逻辑（不含任何 IO）。
 * <p>
 * 职责：
 * <ul>
 *   <li>按 stages 顺序推进，跳过没有候选题的阶段；</li>
 *   <li>每个阶段从该阶段候选池按当前难度就近取题，最多取 askNum 道；</li>
 *   <li>进入新阶段时把难度重置为 medium、连对/连错清零（阶段间不继承）；</li>
 *   <li>依据每题得分调整本阶段内的后续难度。</li>
 * </ul>
 * 把这套逻辑从 {@code interview} 的 IO（提问/评分/回调）中剥离出来，使其可独立测试。
 */
class StageScheduler {

    /** 单个面试阶段的配置：题型 + 该阶段实际提问数（从候选池抽取的上限）。 */
    record StageConfig(String type, int askNum) {}

    /** 依据当前难度与连对/连错次数，返回下一题难度。由调用方注入（生产环境包装 QuestionPlanner.adjustDifficulty）。 */
    @FunctionalInterface
    interface AdjustFunc {
        String adjust(String current, int consecRight, int consecWrong);
    }

    /** 面试阶段顺序与每阶段提问数：basic → experience → design。 */
    static final List<StageConfig> DEFAULT_STAGES = List.of(
            new StageConfig("basic", 8),
            new StageConfig("experience", 5),
            new StageConfig("design", 2)
    );

    private final List<StageConfig> stages;
    private final Map<String, List<PlannedQuestion>> byType;
    private final AdjustFunc adjust;

    private int stageIdx;
    private QuestionPool pool;
    private int stageAsked;
    private boolean started;

    private String difficulty;
    private int consecRight;
    private int consecWrong;

    /** 按题型给候选题分组，构建调度器。 */
    StageScheduler(List<StageConfig> stages, List<PlannedQuestion> questions, AdjustFunc adjust) {
        this.stages = stages;
        this.adjust = adjust;
        this.byType = new LinkedHashMap<>();
        for (PlannedQuestion q : questions) {
            byType.computeIfAbsent(q.getType(), k -> new ArrayList<>()).add(q);
        }
    }

    /** 预计算实际提问总数（每阶段取 min(askNum, 候选库存) 之和）。 */
    int totalToAsk() {
        int total = 0;
        for (StageConfig st : stages) {
            int n = byType.getOrDefault(st.type(), List.of()).size();
            total += Math.min(n, st.askNum());
        }
        return total;
    }

    /** 取出的下一题（含其难度），全部阶段问完返回 null。进入新阶段时内部自动重置难度状态。 */
    Picked next() {
        while (true) {
            // 尚未开始、当前阶段已抽满、或当前池已空 → 推进到下一个可用阶段。
            if (pool == null || stageAsked >= stages.get(stageIdx).askNum() || pool.isEmpty()) {
                if (!advanceStage()) {
                    return null;
                }
            }
            PlannedQuestion picked = pool.next(difficulty);
            if (picked == null) {
                pool = null; // 当前阶段候选已耗尽，强制推进到下一阶段
                continue;
            }
            stageAsked++;
            // 目标难度与实际难度都要返回：两者不一致说明目标难度桶已空、QuestionPool 做了
            // 就近 fallback。此前这里只返回目标难度，导致日志/前端显示「已降到 easy」而
            // 实际问的是 medium 甚至 hard 题——配额缺失被完全静默地吸收掉，线上无从察觉。
            return new Picked(picked, difficulty, picked.getDifficulty());
        }
    }

    /** 推进到下一个有候选题的阶段，并重置阶段内难度状态。返回 false 表示没有更多阶段。 */
    private boolean advanceStage() {
        int from = started ? stageIdx + 1 : 0;
        for (int i = from; i < stages.size(); i++) {
            List<PlannedQuestion> candidates = byType.get(stages.get(i).type());
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            stageIdx = i;
            pool = new QuestionPool(candidates);
            stageAsked = 0;
            // 进入新阶段：难度调节独立重置，不参考上一阶段。
            difficulty = "medium";
            consecRight = 0;
            consecWrong = 0;
            started = true;
            return true;
        }
        return false;
    }

    /**
     * 反馈本题得分，更新本阶段内难度（供下一次 next 使用）。
     * 得分 >= 70 记为答好，否则记为答得不好。
     */
    void record(double score) {
        if (score >= 70) {
            consecRight++;
            consecWrong = 0;
        } else {
            consecWrong++;
            consecRight = 0;
        }
        difficulty = adjust.adjust(difficulty, consecRight, consecWrong);
    }

    int getConsecRight() {
        return consecRight;
    }

    int getConsecWrong() {
        return consecWrong;
    }

    /**
     * next() 的返回值：被抽中的题目 + 抽取时的目标难度 + 题目的实际难度。
     *
     * @param targetDifficulty 难度调节算出的<b>期望</b>难度档
     * @param actualDifficulty 实际取到的题目难度；与 target 不同即表示发生了就近 fallback
     *                         （目标难度桶已空），意味着该档候选方向不足、难度调节精度下降
     */
    record Picked(PlannedQuestion question, String targetDifficulty, String actualDifficulty) {

        /** 是否发生了就近降级/升级取题。 */
        boolean fellBack() {
            return targetDifficulty != null && !targetDifficulty.equals(actualDifficulty);
        }

        /** 兼容旧调用点：对外呈现的难度以【实际题目难度】为准，避免报告与题目自相矛盾。 */
        String difficulty() {
            return actualDifficulty != null ? actualDifficulty : targetDifficulty;
        }
    }
}
