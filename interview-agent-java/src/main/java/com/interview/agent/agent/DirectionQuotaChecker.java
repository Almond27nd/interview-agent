/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.agent;

import com.interview.agent.memory.MemoryWriteGate;
import com.interview.agent.model.QuestionDirection;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 出题方向的<b>配额校验器</b>（Phase 1 的规则层质检）。
 *
 * <h2>为什么需要它：一份「有结构的订单」，此前收货时不验货</h2>
 * Phase 1 要求的不是「31 个方向」这一个数字，而是 <b>(type × difficulty) 9 个格子</b>的分档配额。
 * 但原实现拿到 LLM 输出后只有一行 {@code log.info(size())}：不校验总数、不校验任何格子、
 * 不校验 {@code type}/{@code difficulty} 字段是否合法，缺了不补、多了不裁。
 * 配额完全靠 Prompt 文字约束 + 让模型自检——<b>而「按 9 个格子逐格计数」恰恰是 LLM 最不擅长的</b>。
 *
 * <h2>违约不会报错，但会静默破坏动态难度调节</h2>
 * 下游 {@code QuestionPool.FALLBACK_ORDER} 在目标难度桶取空时会就近降级取题。这个 fallback
 * 本身是好设计（保证有题可问），但它把配额缺失<b>彻底静默化</b>了：
 * <ul>
 *   <li>若某档缺题（如 basic 只出了 5 条）：{@code StageScheduler.totalToAsk()} 取
 *       {@code min(库存, askNum)}，面试题数<b>悄悄变少</b>，报告维度跟着缩水；</li>
 *   <li>若总数够但分布歪（如 basic 15 条全是 hard）：候选人连错两题、难度已降到 easy，
 *       而 easy/medium 桶皆空 → fallback 仍给出 hard 题，<b>候选人越答越崩</b>；</li>
 *   <li>更糟的是记录也是错的：{@code Picked.difficulty} 记的是「目标难度」而非题目实际难度，
 *       日志与前端显示「已降到 easy」，实际问的却是 hard 题。</li>
 * </ul>
 * 整场面试正常跑完、不报任何错，但「动态难度调节」这个核心能力已经失效——线上跑一百场也发现不了。
 * 因此本类的目标不是「保证一定有 31 条」，而是<b>让违约无法静默通过</b>。
 *
 * <h2>为什么是规则层，而不是再加一个 Critic / ReAct</h2>
 * 与 {@link QuestionRuleChecker}（出题审查的规则层）、{@code MemoryWriteGate}（记忆写入的规则层）
 * 同一套方法论：<b>机械可判定的用规则（零 token、零误判），需要理解语义的才交给模型。</b>
 * 配额是纯计数，让一个「数不准数」的模型去校验自己数不准的结果，逻辑上不成立。
 * 且纯函数保证了可复现性——只要模型首轮输出相同，后续处理完全确定。
 *
 * <h2>本类是配额的唯一事实源</h2>
 * 改配额只需动 {@link #QUOTA}，Prompt 里的数量约束文案由 {@link #describeQuota()} 生成。
 * 此前那段文案把 {@code 5 个×3 / 共 15 个 / 4 个×3 / 共 12 个 / 总数 31} 手写了 5 处，
 * 改配额要同步改 5 处，漏一处就自相矛盾。
 *
 * <p>{@code final} 静态工具类，无状态、无 IO，可独立单元测试。
 */
@Slf4j
public final class DirectionQuotaChecker {

    private DirectionQuotaChecker() {
    }

    /** 合法的题型（顺序即 Prompt 中的展示顺序，也是面试阶段顺序）。 */
    public static final List<String> TYPES = List.of("basic", "experience", "design");

    /** 合法的难度档（顺序即由易到难）。 */
    public static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");

    /**
     * 配额表：type → difficulty → 期望方向数。<b>这是配额的唯一定义处。</b>
     *
     * <p>为什么要铺这么多（31 个方向而面试只问 15 道）：这是面试用的<b>候选题池</b>，
     * {@code StageScheduler} 会按候选人实时表现自适应抽取对应难度的题（答得好升档、差降档），
     * 并不要求问完。每档都必须铺满足够候选——同一难度若只有一两个，连续答对/答错时
     * 就会无题可抽、难度调节形同虚设。design 类刻意不设 easy：过于简单的系统设计题
     * 没有考察价值。
     */
    private static final Map<String, Map<String, Integer>> QUOTA;

    static {
        Map<String, Map<String, Integer>> q = new LinkedHashMap<>();
        q.put("basic", orderedQuota(5, 5, 5));
        q.put("experience", orderedQuota(4, 4, 4));
        q.put("design", orderedQuota(0, 2, 2));
        QUOTA = q;
    }

    /** 按 easy/medium/hard 顺序构造单个 type 的配额（数量为 0 的档位不入表）。 */
    private static Map<String, Integer> orderedQuota(int easy, int medium, int hard) {
        Map<String, Integer> m = new LinkedHashMap<>();
        int[] counts = {easy, medium, hard};
        for (int i = 0; i < DIFFICULTIES.size(); i++) {
            if (counts[i] > 0) {
                m.put(DIFFICULTIES.get(i), counts[i]);
            }
        }
        return m;
    }

    /** 期望的方向总数（由配额表求和得出，不硬编码）。 */
    public static int expectedTotal() {
        return QUOTA.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(Integer::intValue)
                .sum();
    }

    /** 某个 (type, difficulty) 的期望数量；非配额组合返回 0。 */
    public static int expected(String type, String difficulty) {
        return QUOTA.getOrDefault(type, Map.of()).getOrDefault(difficulty, 0);
    }

    /** 格子 key，形如 {@code basic/easy}。 */
    public static String cellKey(String type, String difficulty) {
        return type + "/" + difficulty;
    }

    /**
     * 生成 Prompt 用的配额约束文案，保证「代码里的配额」与「发给模型的要求」永远一致。
     */
    public static String describeQuota() {
        StringBuilder sb = new StringBuilder();
        QUOTA.forEach((type, byDiff) -> {
            int subtotal = byDiff.values().stream().mapToInt(Integer::intValue).sum();
            List<String> parts = new ArrayList<>();
            byDiff.forEach((diff, n) -> parts.add(diff + " " + n + " 个"));
            sb.append(String.format("  · type = \"%s\"：%s，共 %d 个%n",
                    type, String.join("、", parts), subtotal));
        });
        sb.append(String.format("因此 directions 总数应为 %d 个。", expectedTotal()));
        return sb.toString();
    }

    /** 生成「自检提示」文案（Prompt 末尾用）。 */
    public static String describeSelfCheck() {
        List<String> parts = new ArrayList<>();
        QUOTA.forEach((type, byDiff) -> {
            List<String> items = new ArrayList<>();
            byDiff.forEach((diff, n) -> items.add(diff + " " + n + " 个"));
            parts.add(String.format("%s 是否 %s？", type, String.join("/", items)));
        });
        return String.join(" ", parts);
    }

    /**
     * ① 字段合法化：剔除 {@code topic} 为空、{@code type}/{@code difficulty} 不在白名单内的方向。
     *
     * <p><b>为什么必须在进桶之前剔掉</b>：{@code QuestionPool} 的构造函数对无法识别的难度值
     * 会<b>静默归入 medium 桶</b>（{@code if (!buckets.containsKey(level)) level = "medium"}）。
     * 模型写个 {@code "简单"} 或 {@code "middle"}，题目就会悄悄跑到错误的难度档里，
     * 既污染难度分布又无从察觉。在这里显式剔除并打日志，是把静默错误变成可观测事件。
     *
     * <p>大小写与首尾空白做容错归一（{@code "Basic"} → {@code "basic"}），
     * 这属于「机械可判定」的书写差异，不该因此丢弃一个内容可能很好的方向。
     */
    public static List<QuestionDirection> normalize(List<QuestionDirection> dirs) {
        List<QuestionDirection> kept = new ArrayList<>();
        if (dirs == null) {
            return kept;
        }
        for (QuestionDirection d : dirs) {
            if (d == null || d.getTopic() == null || d.getTopic().isBlank()) {
                log.warn("[DirectionQuota] 剔除 topic 缺失的方向");
                continue;
            }
            String type = lower(d.getType());
            String diff = lower(d.getDifficulty());
            if (!TYPES.contains(type)) {
                log.warn("[DirectionQuota] 剔除非法 type=\"{}\" 的方向（topic={}）", d.getType(), d.getTopic());
                continue;
            }
            if (!DIFFICULTIES.contains(diff)) {
                log.warn("[DirectionQuota] 剔除非法 difficulty=\"{}\" 的方向（topic={}）",
                        d.getDifficulty(), d.getTopic());
                continue;
            }
            if (expected(type, diff) == 0) {
                log.warn("[DirectionQuota] 剔除配额外组合 {}（topic={}）", cellKey(type, diff), d.getTopic());
                continue;
            }
            d.setType(type);
            d.setDifficulty(diff);
            kept.add(d);
        }
        return kept;
    }

    /**
     * ② 超额裁剪：某格子超出配额时，保留靠前的、丢弃多余的。
     *
     * <p><b>必须先裁再算缺口</b>：LLM 常见的违约形态是「总数对了但分布歪」——
     * hard 多 5 条、easy 少 5 条同时存在。若不先裁，{@link #diff} 算出的缺口会被超额抵消，
     * 补全后总数超标；先裁再补才能收敛到精确配额。
     */
    public static List<QuestionDirection> trimOverflow(List<QuestionDirection> dirs) {
        List<QuestionDirection> kept = new ArrayList<>();
        if (dirs == null) {
            return kept;
        }
        Map<String, Integer> used = new LinkedHashMap<>();
        for (QuestionDirection d : dirs) {
            String key = cellKey(d.getType(), d.getDifficulty());
            int quota = expected(d.getType(), d.getDifficulty());
            int cur = used.getOrDefault(key, 0);
            if (cur >= quota) {
                log.warn("[DirectionQuota] {} 已达配额 {}，裁剪超额方向: {}", key, quota, d.getTopic());
                continue;
            }
            used.put(key, cur + 1);
            kept.add(d);
        }
        return kept;
    }

    /**
     * ③ 逐格比对，返回<b>缺口</b>（仅含缺量的格子，value 为还需补充的数量）。
     *
     * <p>只查总数是没用的：31 条里 20 条 hard 也能让总数校验通过，
     * 但难度调节已经失效。因此必须逐格。
     */
    public static Map<String, Integer> diff(List<QuestionDirection> dirs) {
        Map<String, Integer> actual = countByCell(dirs);
        Map<String, Integer> gap = new LinkedHashMap<>();
        QUOTA.forEach((type, byDiff) -> byDiff.forEach((difficulty, want) -> {
            String key = cellKey(type, difficulty);
            int have = actual.getOrDefault(key, 0);
            if (have < want) {
                gap.put(key, want - have);
            }
        }));
        return gap;
    }

    /** 各格子的实际数量统计（仅统计合法组合，供日志与测试使用）。 */
    public static Map<String, Integer> countByCell(List<QuestionDirection> dirs) {
        Map<String, Integer> actual = new LinkedHashMap<>();
        if (dirs == null) {
            return actual;
        }
        for (QuestionDirection d : dirs) {
            if (d == null) {
                continue;
            }
            String key = cellKey(lower(d.getType()), lower(d.getDifficulty()));
            actual.merge(key, 1, Integer::sum);
        }
        return actual;
    }

    /**
     * ④ 与既有方向去重：{@code topic} 归一后重复的直接丢弃。
     *
     * <p><b>不能用字符串 equals</b>：定向补全时模型很可能把「MySQL索引」换个写法写成
     * 「MySQL 索引优化」交回来。这里直接复用记忆写入门控的 {@code normalizeRaw}（字符级归一）
     * 与 {@code canonicalize}（同义词表归一）——这两个函数本来就是为「同一考点的不同写法」
     * 而写的，不必新造一套判重逻辑。这也是那张同义词表的第三个使用场景
     * （写入侧、召回侧、方向去重）。
     */
    public static List<QuestionDirection> dedup(List<QuestionDirection> incoming,
                                                Collection<QuestionDirection> existing) {
        List<QuestionDirection> kept = new ArrayList<>();
        if (incoming == null) {
            return kept;
        }
        Set<String> seen = new LinkedHashSet<>();
        if (existing != null) {
            for (QuestionDirection d : existing) {
                if (d != null) {
                    seen.add(canonicalKey(d.getTopic()));
                }
            }
        }
        for (QuestionDirection d : incoming) {
            if (d == null || d.getTopic() == null) {
                continue;
            }
            String key = canonicalKey(d.getTopic());
            if (!seen.add(key)) {
                log.warn("[DirectionQuota] 补全结果与既有方向重复，丢弃: {}", d.getTopic());
                continue;
            }
            kept.add(d);
        }
        return kept;
    }

    /**
     * 判定某组方向是否已被覆盖（用于统计薄弱点覆盖率）。
     * 命中条件：方向的 topic 或任一 skill 与目标 topic 归一后相等或互相包含。
     */
    public static boolean covers(List<QuestionDirection> dirs, String weakTopic) {
        if (dirs == null || weakTopic == null || weakTopic.isBlank()) {
            return false;
        }
        String target = canonicalKey(weakTopic);
        String targetRaw = MemoryWriteGate.normalizeRaw(weakTopic);
        if (target.isEmpty() && targetRaw.isEmpty()) {
            return false;
        }
        for (QuestionDirection d : dirs) {
            if (d == null) {
                continue;
            }
            if (matches(d.getTopic(), target, targetRaw)) {
                return true;
            }
            if (d.getSkills() != null) {
                for (String s : d.getSkills()) {
                    if (matches(s, target, targetRaw)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matches(String candidate, String target, String targetRaw) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (canonicalKey(candidate).equals(target)) {
            return true;
        }
        String raw = MemoryWriteGate.normalizeRaw(candidate);
        if (raw.isEmpty() || targetRaw.isEmpty()) {
            return false;
        }
        return raw.contains(targetRaw) || targetRaw.contains(raw);
    }

    /**
     * 归一化判重 key：先走同义词表，再做字符级归一。
     * <p>刻意给 {@code canonicalize} 传空集合：只要「同义词表映射」这一档能力，
     * 不要它的 bigram 近似合并——短 topic 的 bigram 相似度天然偏高，
     * 「MySQL索引」与「MySQL事务」这类前缀相同但考点不同的方向会被误并
     * （与 {@code MemoryRecallService.lexicalScore} 的处理保持一致）。
     */
    private static String canonicalKey(String topic) {
        if (topic == null || topic.isBlank()) {
            return "";
        }
        return MemoryWriteGate.normalizeRaw(MemoryWriteGate.canonicalize(topic, List.of()));
    }

    /** 缺口的可读描述，用于日志与补全 Prompt。 */
    public static String describeGap(Map<String, Integer> gap) {
        if (gap == null || gap.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        gap.forEach((k, n) -> parts.add(k + " 还需 " + n + " 个"));
        return String.join("；", parts);
    }

    /** 缺口涉及的 type 集合（用于判断是否需要在补全 Prompt 里强调「严禁杜撰简历内容」）。 */
    public static Set<String> gapTypes(Map<String, Integer> gap) {
        Set<String> types = new HashSet<>();
        if (gap != null) {
            gap.keySet().forEach(k -> types.add(k.split("/")[0]));
        }
        return types;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
