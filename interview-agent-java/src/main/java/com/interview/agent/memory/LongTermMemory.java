/**
 */
package com.interview.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆：管理用户画像和面试历史。
 *
 * <p><b>记忆模型（证据型记忆）</b>：
 * <ul>
 *   <li>每次考察落一条 {@link UserProfile.Evidence}（得分 + 时间 + 题目难度）；</li>
 *   <li>掌握度是<b>连续置信度</b>（{@link UserProfile.WeakPoint#mastery()}，按时间指数衰减 +
 *       难度加权 + 一致性惩罚合成），不再是「在/不在薄弱点列表」的二值判断；</li>
 *   <li>达标（{@code mastery >= 0.8}）<b>不删除</b>，只打 {@code masteredAt} 软失效标记并保留全部历史；
 *       一旦再次低分即识别为<b>顽固薄弱点</b>并提权——这是软失效相对物理删除的直接收益；</li>
 *   <li>召回按 {@link UserProfile.WeakPoint#priority()} 排序，{@code hitCount}/{@code wrongCount}
 *       真正参与决策，而不再只是 Prompt 里的展示文本。</li>
 * </ul>
 *
 * <p><b>为什么要这么改</b>（原实现的三个硬伤）：
 * <ol>
 *   <li>{@code score >= 80} 时 {@code it.remove()} <b>物理删除</b>：某知识点历史错 5 次，
 *       这次蒙对一道 easy 题就被彻底遗忘，下次再错时 {@code wrongCount} 从 1 重新开始，
 *       系统<b>永远识别不出顽固薄弱点</b>——而顽固薄弱点恰恰是模拟面试最该反复考的；</li>
 *   <li>排序只用最新一次得分，{@code hitCount}/{@code wrongCount} 白存不参与决策，
 *       于是「考 1 次错 1 次得 55」会排在「考 6 次错 5 次得 58」前面，明显是反的；</li>
 *   <li>30 天硬淘汰是<b>二值切断</b>（第 29 天全权重、第 31 天凭空消失），而遗忘应该是连续的。</li>
 * </ol>
 * 核心判断：<b>长期记忆最危险的不是忘记，而是自信地记住了过期的结论。</b>
 *
 * <p><b>写入侧</b>统一经过 {@link MemoryWriteGate}：拦截超时产生的伪证据、并对 topic 做实体归一，
 * 避免「MySQL索引 / MySQL 索引优化 / 索引失效」分裂成三条把 Top N 配额挤满。
 */
@Slf4j
@Component
public class LongTermMemory {

    /**
     * 薄弱点最长保留时长。
     * <p>注意语义已变化：它不再是「参与出题的硬性时间窗」（时效性已由
     * {@link UserProfile.WeakPoint#mastery()} 的指数衰减连续表达），
     * 这里只作为<b>存储侧的清理边界</b>，防止画像 JSON 无界增长。
     * 因此取值比原来的 30 天更宽松。
     */
    private static final Duration WEAK_POINT_MAX_AGE = Duration.ofDays(180);

    /** 最终送进出题 Prompt 的薄弱点数量上限。 */
    private static final int WEAK_POINT_TOP_N = 10;

    /**
     * 交给 {@link MemoryRecallService} 的<b>召回候选池</b>上限，刻意大于 {@link #WEAK_POINT_TOP_N}。
     *
     * <p><b>为什么必须分成两个数</b>：本层<b>不知道当前 JD</b>（{@code getWeakPoints} 的签名里
     * 只有 userID），只能按 {@link UserProfile.WeakPoint#priority()} 这个「与岗位无关的固有优先级」
     * 排序。如果在这里就硬截断到 10 条，就形成了一个致命的顺序错误：
     * <b>截断发生在唯一不知道 JD 的那一层，而判断 JD 相关性的那一层反而没有筛选空间。</b>
     *
     * <p>具体后果：候选人历史上主要面 Go 岗位、攒了一批高 priority 的 Go 薄弱点，
     * 现在来面 Java 岗——全局 Top10 可能被 Go 占满，而排在第 13 名、与本次 JD 强相关的
     * 「MySQL索引」<b>根本进不了候选集</b>。此时三路混合召回拿到的 10 条全都不相关，
     * {@code relevant} 可能为 0 条：召回做得再好也救不回来，因为丢失发生在召回之前。
     *
     * <p>放宽到 30 之后，本层退化为「排除已掌握项 + 按固有优先级粗筛」，
     * 最终收敛交由知道 JD 的三路 RRF 完成（30 进 10，压缩比 3:1，是检索系统里常规的
     * 召回/精排配比）。此前 {@code WEAK_POINT_TOP_N == FUSE_TOP_K == 10} 导致 RRF
     * 「10 进 10、一条都淘汰不掉」，融合环节实际只做了重排与打标签，并没有筛选。
     *
     * <p><b>代价</b>：语义通道的批量 embedding 文本数从约 25 条增至约 45 条。按实测单次批量
     * 400~450ms，仍在 {@code MemoryRecallService.EMBEDDING_TIMEOUT_MS = 3000} 预算内。
     */
    private static final int WEAK_POINT_CANDIDATE_POOL = 30;

    /** 判定为顽固薄弱点的答错次数阈值。 */
    private static final int STUBBORN_WRONG_COUNT = 3;

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final CombinedStore store;

    public LongTermMemory(CombinedStore store) {
        this.store = store;
    }

    /**
     * 获取用户画像
     */
    public UserProfile getProfile(String userID) {
        // 先查内存缓存
        UserProfile profile = profiles.get(userID);
        if (profile != null) {
            return profile;
        }

        // 尝试从持久化存储加载
        if (store != null) {
            profile = store.loadProfile(userID);
            if (profile != null) {
                profiles.put(userID, profile);
                return profile;
            }
        }

        // 创建新用户画像
        profile = UserProfile.builder()
                .userId(userID)
                .skillLevel(new HashMap<>())
                .weakPoints(new ArrayList<>())
                .interviewHist(new ArrayList<>())
                .updatedAt(LocalDateTime.now())
                .build();
        profiles.put(userID, profile);
        return profile;
    }

    /**
     * 兼容旧签名的入口：无难度、无场次信息时使用（等价于 medium 难度的一条证据）。
     *
     * @deprecated 建议改用
     *         {@link #recordEvidence(String, String, double, String, String)}，
     *         它能带上题目难度与场次，让掌握度置信度的计算更准确。
     */
    @Deprecated
    public void updateWeakPoints(String userID, String topic, double score) {
        recordEvidence(userID, topic, score, "medium", null);
    }

    /**
     * 记录一次考察证据，并据此更新薄弱点档案。
     *
     * <p>调用方应先用 {@link MemoryWriteGate#acceptAsEvidence(boolean, String)} 判定证据是否可信
     * （超时未作答不构成能力证据），本方法只负责「可信证据」的入库与聚合。
     *
     * @param userID     用户
     * @param rawTopic   原始 topic（题目 skills 标签，可能是自由文本）
     * @param score      本次得分
     * @param difficulty 题目难度 easy/medium/hard，决定该条证据的说服力权重
     * @param sessionId  面试场次，便于结论回溯到具体证据
     */
    public void recordEvidence(String userID, String rawTopic, double score,
                               String difficulty, String sessionId) {
        if (rawTopic == null || rawTopic.isBlank()) {
            return;
        }
        UserProfile profile = profiles.computeIfAbsent(userID, this::newProfile);

        synchronized (profile) {
            List<UserProfile.WeakPoint> weakPoints = profile.getWeakPoints();
            if (weakPoints == null) {
                weakPoints = new ArrayList<>();
                profile.setWeakPoints(weakPoints);
            }

            // === M2 写入门控：topic 实体归一，避免同一知识点分裂成多条 ===
            List<String> existingTopics = weakPoints.stream()
                    .map(UserProfile.WeakPoint::getTopic)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            String topic = MemoryWriteGate.canonicalize(rawTopic, existingTopics);
            if (topic.isBlank()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            UserProfile.WeakPoint wp = weakPoints.stream()
                    .filter(w -> w.getTopic() != null && w.getTopic().equals(topic))
                    .findFirst()
                    .orElse(null);

            boolean isWrong = score < MemoryWriteGate.WRONG_SCORE_THRESHOLD;

            if (wp == null) {
                // 只有「答错」才值得新建一条薄弱点档案；答对的知识点无需占用记忆
                if (!isWrong) {
                    return;
                }
                wp = UserProfile.WeakPoint.builder()
                        .topic(topic)
                        .firstSeen(now)
                        .evidences(new ArrayList<>())
                        .aliases(new ArrayList<>())
                        .build();
                weakPoints.add(wp);
            }

            // 记录别名（原始写法与规范写法不同时留档，便于排查归一是否合理）
            if (!topic.equals(rawTopic.trim())) {
                if (wp.getAliases() == null) {
                    wp.setAliases(new ArrayList<>());
                }
                String alias = rawTopic.trim();
                if (!wp.getAliases().contains(alias)) {
                    wp.getAliases().add(alias);
                }
            }

            // === 复发检测：已判定掌握后又答错 → 顽固薄弱点 ===
            // 这正是「软失效而非物理删除」换来的能力：原实现删除后再错只会被当成全新薄弱点。
            if (wp.getMasteredAt() != null && isWrong) {
                wp.setRelapseCount(wp.getRelapseCount() + 1);
                wp.setMasteredAt(null);   // 复活，重新进入观察
                wp.setStubborn(true);
                log.info("[LongTermMemory] 薄弱点复发，标记为顽固: user={}, topic={}, 复发次数={}",
                        userID, topic, wp.getRelapseCount());
            }

            // === 追加证据（保留最近 MAX_EVIDENCES 条，防止画像 JSON 无界增长）===
            if (wp.getEvidences() == null) {
                wp.setEvidences(new ArrayList<>());
            }
            wp.getEvidences().add(UserProfile.Evidence.builder()
                    .score(score)
                    .askedAt(now)
                    .difficulty(difficulty)
                    .sessionId(sessionId)
                    .build());
            while (wp.getEvidences().size() > UserProfile.WeakPoint.MAX_EVIDENCES) {
                wp.getEvidences().remove(0);
            }

            // === 维护聚合字段（旧字段保留：Prompt 渲染与旧数据兼容仍在用）===
            wp.setScore(score);
            wp.setHitCount(wp.getHitCount() + 1);
            if (isWrong) {
                wp.setWrongCount(wp.getWrongCount() + 1);
            }
            wp.setLastSeen(now);
            if (wp.getFirstSeen() == null) {
                wp.setFirstSeen(now);
            }

            // 反复答错也算顽固（不必等到「掌握后复发」）
            if (wp.getWrongCount() >= STUBBORN_WRONG_COUNT) {
                wp.setStubborn(true);
            }

            // === 软失效判定：达标只打标记，绝不物理删除 ===
            if (wp.getMasteredAt() == null
                    && wp.mastery() >= UserProfile.WeakPoint.MASTERY_THRESHOLD) {
                wp.setMasteredAt(now);
                log.info("[LongTermMemory] 薄弱点判定为已掌握（软失效，保留历史）: user={}, topic={}, mastery={}",
                        userID, topic, String.format("%.2f", wp.mastery()));
            }

            profile.setUpdatedAt(now);
            pruneExpired(profile);
        }

        // 持久化
        if (store != null) {
            store.saveProfile(profile);
        }
    }

    /**
     * 添加面试记录
     */
    public void addInterviewRecord(String userID, UserProfile.InterviewRecord record) {
        UserProfile profile = profiles.computeIfAbsent(userID, this::newProfile);

        synchronized (profile) {
            if (profile.getInterviewHist() == null) {
                profile.setInterviewHist(new ArrayList<>());
            }
            profile.getInterviewHist().add(record);
            profile.setUpdatedAt(LocalDateTime.now());
        }

        if (store != null) {
            store.saveProfile(profile);
        }
    }

    /**
     * 按技能回填技能等级（skillLevel）。
     * <p>
     * 面试结束后，按题目 skills 标签把本场问答分组聚合出每个技能的平均得分，
     * 映射成 beginner(&lt;60) / intermediate(60~80) / advanced(&gt;=80) 写回画像。
     * topic 同样走 {@link MemoryWriteGate} 的实体归一，与薄弱点使用同一套规范名，
     * 避免两个字段里出现同一知识点的不同写法。
     */
    public void updateSkillLevels(String userID, Map<String, List<Double>> skillScores) {
        if (skillScores == null || skillScores.isEmpty()) {
            return;
        }
        UserProfile profile = profiles.computeIfAbsent(userID, this::newProfile);

        synchronized (profile) {
            Map<String, String> skillLevel = profile.getSkillLevel();
            if (skillLevel == null) {
                skillLevel = new HashMap<>();
                profile.setSkillLevel(skillLevel);
            }
            // 归一时把「已有薄弱点 topic + 已有技能名」一起作为参照，保证两处命名一致
            List<String> known = new ArrayList<>(skillLevel.keySet());
            if (profile.getWeakPoints() != null) {
                profile.getWeakPoints().stream()
                        .map(UserProfile.WeakPoint::getTopic)
                        .filter(Objects::nonNull)
                        .forEach(known::add);
            }
            for (Map.Entry<String, List<Double>> entry : skillScores.entrySet()) {
                String skill = MemoryWriteGate.canonicalize(entry.getKey(), known);
                if (skill.isBlank()) {
                    continue;
                }
                double avg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                String level = avg >= 80 ? "advanced" : (avg >= 60 ? "intermediate" : "beginner");
                skillLevel.put(skill, level);
            }
            profile.setUpdatedAt(LocalDateTime.now());
        }

        if (store != null) {
            store.saveProfile(profile);
        }
    }

    /**
     * 获取用于出题的薄弱点：过滤已掌握项 + 按召回优先级排序 + Top N。
     *
     * <p><b>使用建议</b>：若下游要接 {@link MemoryRecallService} 做 JD 相关性召回，
     * 请改用 {@link #getWeakPointCandidates(String)}——本方法在「不知道 JD」的前提下
     * 就截断到 10 条，会在召回之前丢掉与当前岗位强相关但固有优先级偏低的条目
     * （原因详见 {@link #WEAK_POINT_CANDIDATE_POOL}）。本方法保留给「不经召回、
     * 直接取最终 Top N」的场景（如画像展示、单测）。
     *
     * <p>与原实现的区别：
     * <ul>
     *   <li>排序键从「最新一次得分」换成 {@link UserProfile.WeakPoint#priority()}，
     *       综合了掌握度置信度、顽固标记、复发次数与错误率——
     *       {@code hitCount}/{@code wrongCount} 终于真正参与决策；</li>
     *   <li>时效性由 {@code mastery()} 的指数衰减连续表达，不再依赖「30 天二值硬淘汰」；</li>
     *   <li>已判定掌握（软失效）的项默认不召回，但记录仍在库中，用于后续复发检测。</li>
     * </ul>
     */
    public List<UserProfile.WeakPoint> getWeakPoints(String userID) {
        return topByPriority(userID, WEAK_POINT_TOP_N);
    }

    /**
     * 获取<b>召回候选池</b>：与 {@link #getWeakPoints} 同样的过滤与排序，但只做粗筛
     * （上限 {@link #WEAK_POINT_CANDIDATE_POOL}），把最终截断留给知道 JD 的三路混合召回。
     *
     * <p>本方法存在的唯一理由是修正一处顺序错误：<b>截断不应发生在不知道 JD 的这一层。</b>
     * 完整推理见 {@link #WEAK_POINT_CANDIDATE_POOL} 的注释。
     */
    public List<UserProfile.WeakPoint> getWeakPointCandidates(String userID) {
        return topByPriority(userID, WEAK_POINT_CANDIDATE_POOL);
    }

    /** 共用实现：排除软失效项 → 按 priority 降序 → 取前 limit 条。 */
    private List<UserProfile.WeakPoint> topByPriority(String userID, int limit) {
        UserProfile profile = getProfile(userID);
        if (profile == null || profile.getWeakPoints() == null) {
            return Collections.emptyList();
        }

        return profile.getWeakPoints().stream()
                .filter(Objects::nonNull)
                .filter(UserProfile.WeakPoint::isActive)
                .sorted(Comparator.comparingDouble(UserProfile.WeakPoint::priority).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 存储侧清理：只丢弃「已掌握且很久没再出现」的记录，防止画像 JSON 无界增长。
     * <p>仍在观察中（未掌握）的薄弱点<b>永不因时间被丢弃</b>——它们的时效性已由
     * 掌握度的指数衰减表达，硬删会重新引入「系统失忆」问题。
     */
    private void pruneExpired(UserProfile profile) {
        List<UserProfile.WeakPoint> weakPoints = profile.getWeakPoints();
        if (weakPoints == null || weakPoints.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        weakPoints.removeIf(wp -> wp.getMasteredAt() != null
                && !wp.isStubborn()
                && wp.getLastSeen() != null
                && Duration.between(wp.getLastSeen(), now).compareTo(WEAK_POINT_MAX_AGE) > 0);
    }

    private UserProfile newProfile(String userId) {
        return UserProfile.builder()
                .userId(userId)
                .skillLevel(new HashMap<>())
                .weakPoints(new ArrayList<>())
                .interviewHist(new ArrayList<>())
                .build();
    }
}
