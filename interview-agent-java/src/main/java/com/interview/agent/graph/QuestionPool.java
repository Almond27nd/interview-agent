/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.graph;

import com.interview.agent.model.PlannedQuestion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按难度分桶的题池，支持按当前难度就近取题。
 * <p>
 * 用于让「动态难度调节」真正生效：每答完一题，依据更新后的 currentDifficulty
 * 从对应难度桶里取下一道题；目标难度桶取空时就近降级/升级到最接近的非空桶。
 */
class QuestionPool {

    /** 每个目标难度取不到题时的就近回退顺序。 */
    private static final Map<String, List<String>> FALLBACK_ORDER = Map.of(
            "easy", List.of("easy", "medium", "hard"),
            "medium", List.of("medium", "easy", "hard"),
            "hard", List.of("hard", "medium", "easy")
    );

    private final Map<String, Deque<PlannedQuestion>> buckets;
    private int remain;

    /**
     * 把题目按难度分桶，桶内保持原相对顺序。
     * difficulty 不是 easy/medium/hard 的题统一归入 medium 桶。
     */
    QuestionPool(List<PlannedQuestion> questions) {
        buckets = new LinkedHashMap<>();
        buckets.put("easy", new ArrayDeque<>());
        buckets.put("medium", new ArrayDeque<>());
        buckets.put("hard", new ArrayDeque<>());
        for (PlannedQuestion q : questions) {
            String level = q.getDifficulty();
            if (!buckets.containsKey(level)) {
                level = "medium";
            }
            buckets.get(level).addLast(q);
            remain++;
        }
    }

    /** 题池是否已取空。 */
    boolean isEmpty() {
        return remain == 0;
    }

    /**
     * 按目标难度取下一题：优先目标难度桶，空则按 FALLBACK_ORDER 就近取。
     * 取出的题会从桶中移除。题池为空时返回 null。
     */
    PlannedQuestion next(String target) {
        List<String> order = FALLBACK_ORDER.getOrDefault(target, FALLBACK_ORDER.get("medium"));
        for (String level : order) {
            Deque<PlannedQuestion> bucket = buckets.get(level);
            if (bucket != null && !bucket.isEmpty()) {
                remain--;
                return bucket.pollFirst();
            }
        }
        return null;
    }
}
