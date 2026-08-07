/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    @JsonProperty("user_id")
    private String userId;

    private String name;

    @JsonProperty("skill_level")
    @Builder.Default
    private Map<String, String> skillLevel = new HashMap<>();  // skill -> beginner/intermediate/advanced

    @JsonProperty("weak_points")
    @Builder.Default
    private List<WeakPoint> weakPoints = new ArrayList<>();

    @JsonProperty("interview_hist")
    @Builder.Default
    private List<InterviewRecord> interviewHist = new ArrayList<>();

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 薄弱点：从「只记最新一次得分的覆盖式结论」升级为「带证据序列与双时间轴的档案」。
     *
     * <p><b>为什么要改</b>：原实现只有 {@code score}（最新得分）+ {@code hitCount}/{@code wrongCount}，
     * 且 {@code score >= 80} 时把整条记录物理删除。这带来两个硬伤：
     * <ol>
     *   <li>一次蒙对（甚至只是一道 easy 题）就让此前累计的错误历史全部消失，
     *       下次再错时 {@code wrongCount} 从 1 重新开始，系统永远识别不出「顽固薄弱点」；</li>
     *   <li>{@code hitCount}/{@code wrongCount} 虽然被维护，却不参与任何排序决策，
     *       只是被拼进 Prompt 文本，等于白存。</li>
     * </ol>
     *
     * <p><b>现在的模型</b>：每次考察落一条 {@link Evidence}（得分 + 时间 + 题目难度），
     * 掌握度由 {@link #mastery()} 依据「时间衰减 + 难度加权 + 一致性」实时算出（连续值，非二值）；
     * 达标不再删除，而是打 {@link #masteredAt} 软失效标记并保留全部历史（对标
     * Zep/Graphiti 的 valid_at / invalid_at 双时间轴思路）——核心判断是：
     * <b>长期记忆最危险的不是忘记，而是自信地记住了过期的结论。</b>
     *
     * <p><b>向后兼容</b>：{@code score}/{@code hitCount}/{@code wrongCount}/{@code lastSeen}
     * 四个旧字段保留并继续维护（DB 里已有的旧 JSON 仍可反序列化，Prompt 渲染也仍在用）；
     * 新字段都有默认值，且类上标了 {@code ignoreUnknown}，新旧数据可双向兼容。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeakPoint {

        /** 归一化后的规范 topic（canonical form），由写入门控保证稳定。 */
        private String topic;

        /** 最近一次得分（旧字段，保留：Prompt 渲染与旧数据兼容仍在用）。 */
        private double score;

        /** 被考察总次数（旧字段，现已真正参与决策：见 {@link #mastery()}）。 */
        @JsonProperty("hit_count")
        private int hitCount;

        /** 答错（<60）次数（旧字段，现已真正参与决策）。 */
        @JsonProperty("wrong_count")
        private int wrongCount;

        /** 最近一次被考察的时间。 */
        @JsonProperty("last_seen")
        private LocalDateTime lastSeen;

        // ===== 以下为证据型记忆新增字段 =====

        /** 首次成为薄弱点的时间（双时间轴的起点）。 */
        @JsonProperty("first_seen")
        private LocalDateTime firstSeen;

        /**
         * 证据序列：每次考察一条。只保留最近 {@link #MAX_EVIDENCES} 条，
         * 避免画像 JSON 无界增长（更早的证据已通过 hitCount/wrongCount 聚合体现）。
         */
        @JsonProperty("evidences")
        @Builder.Default
        private List<Evidence> evidences = new ArrayList<>();

        /**
         * 软失效时间戳：非 null 表示「曾被判定为已掌握」。
         * 替代原来的物理删除——历史全部保留，才能识别复发。
         */
        @JsonProperty("mastered_at")
        private LocalDateTime masteredAt;

        /** 判定掌握后又再次低分的次数（复发次数）。 */
        @JsonProperty("relapse_count")
        private int relapseCount;

        /**
         * 顽固薄弱点标记：反复错、或掌握后又复发。
         * 这是「软失效而非删除」换来的直接收益，也是模拟面试最该重点复考的信号。
         */
        @JsonProperty("stubborn")
        private boolean stubborn;

        /** 被实体归一合并进来的等价写法（如「MySQL 索引优化」并入「MySQL索引」）。 */
        @JsonProperty("aliases")
        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        /** 证据序列保留上限。 */
        public static final int MAX_EVIDENCES = 12;

        /** 掌握度置信度的时间衰减半衰期（天）：越久远的证据权重越低，替代原来「30 天二值硬淘汰」。 */
        public static final double HALF_LIFE_DAYS = 21.0;

        /** 达到该掌握度即视为已掌握（打软失效标记）。 */
        public static final double MASTERY_THRESHOLD = 0.8;

        /**
         * 计算掌握度置信度 ∈ [0,1]，由三项合成：
         * <ol>
         *   <li><b>时间衰减的加权平均得分</b>：每条证据的权重 = 0.5^(距今天数 / 半衰期)，
         *       近期表现主导，久远证据自然淡出——而不是第 29 天全权重、第 31 天凭空消失；</li>
         *   <li><b>题目难度加权</b>：hard 题答对比 easy 题答对更能证明掌握
         *       （easy×0.7 / medium×1.0 / hard×1.3），避免「蒙对一道简单题就算掌握」；</li>
         *   <li><b>一致性惩罚</b>：得分方差大说明时对时错、掌握不稳，按标准差下调置信度，
         *       促使这类知识点被再次考察验证。</li>
         * </ol>
         * 无证据时退化为按旧字段 {@code score} 估算，保证旧数据也能参与排序。
         */
        public double mastery() {
            if (evidences == null || evidences.isEmpty()) {
                return Math.max(0.0, Math.min(1.0, score / 100.0));
            }
            LocalDateTime now = LocalDateTime.now();
            double weightedSum = 0.0;
            double weightTotal = 0.0;
            for (Evidence ev : evidences) {
                if (ev == null) {
                    continue;
                }
                double days = ev.getAskedAt() == null ? 0.0
                        : Math.max(0.0, Duration.between(ev.getAskedAt(), now).toHours() / 24.0);
                double recency = Math.pow(0.5, days / HALF_LIFE_DAYS);
                double weight = recency * ev.difficultyWeight();
                weightedSum += (ev.getScore() / 100.0) * weight;
                weightTotal += weight;
            }
            if (weightTotal <= 0) {
                return Math.max(0.0, Math.min(1.0, score / 100.0));
            }
            double base = weightedSum / weightTotal;

            // 一致性惩罚：得分波动越大，置信度越低（最多下调 0.15）
            double mean = evidences.stream().filter(Objects::nonNull)
                    .mapToDouble(e -> e.getScore() / 100.0).average().orElse(base);
            double variance = evidences.stream().filter(Objects::nonNull)
                    .mapToDouble(e -> Math.pow(e.getScore() / 100.0 - mean, 2)).average().orElse(0.0);
            double penalty = Math.min(0.15, Math.sqrt(variance) * 0.3);

            return Math.max(0.0, Math.min(1.0, base - penalty));
        }

        /** 是否仍处于「观察中」（未被判定掌握，或掌握后已复发）。 */
        public boolean isActive() {
            return masteredAt == null;
        }

        /**
         * 召回优先级：越大越该被重点考察。
         * 掌握度越低越优先；顽固薄弱点显著提权；复发次数进一步加权。
         * 这样 {@code hitCount}/{@code wrongCount} 终于真正参与决策，而不只是 Prompt 里的展示文本。
         */
        public double priority() {
            double p = 1.0 - mastery();
            if (stubborn) {
                p += 0.35;
            }
            p += Math.min(0.3, relapseCount * 0.15);
            if (hitCount > 0) {
                p += Math.min(0.2, (double) wrongCount / hitCount * 0.2);
            }
            return p;
        }
    }

    /** 单次考察留下的证据：这是「证据型记忆」的最小单元。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Evidence {

        /** 本次得分。 */
        private double score;

        /** 考察时间。 */
        @JsonProperty("asked_at")
        private LocalDateTime askedAt;

        /** 题目难度：easy/medium/hard，决定这条证据的说服力权重。 */
        private String difficulty;

        /** 来源面试场次，便于追溯（raw log 思路：结论可回溯到具体证据）。 */
        @JsonProperty("session_id")
        private String sessionId;

        /** 难度权重：hard 题的表现比 easy 题更有说服力。 */
        public double difficultyWeight() {
            if (difficulty == null) {
                return 1.0;
            }
            return switch (difficulty.toLowerCase()) {
                case "easy" -> 0.7;
                case "hard" -> 1.3;
                default -> 1.0;
            };
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewRecord {
        @JsonProperty("session_id")
        private String sessionId;
        private String position;
        @JsonProperty("overall_score")
        private double overallScore;
        private LocalDateTime date;
    }
}
