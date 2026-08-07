/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory;

import com.interview.agent.rag.RRFusion;
import com.interview.agent.rag.RagDocument;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 记忆混合召回（M3）—— 决定「本场面试该重点考候选人哪些历史薄弱点」。
 *
 * <h2>要解决的问题</h2>
 * 原实现用双向字符串包含判断薄弱点与当前 JD 是否相关：
 * <pre>{@code
 * if (topicLower.contains(skill) || skill.contains(topicLower)) { ... }
 * }</pre>
 * 这是纯 keyword 匹配，对同义词与上下位概念完全无能为力——薄弱点「分布式事务」与 JD 技能
 * 「Seata」「两阶段提交」字符串互不包含，会被判为不相关而降权，而它们其实是同一个考点。
 *
 * <h2>三路召回 + RRF 融合</h2>
 * <table border="1">
 *   <tr><th>通道</th><th>解决什么</th><th>为什么不能只靠它</th></tr>
 *   <tr><td><b>语义</b>（embedding 余弦相似度）</td>
 *       <td>同义词、上下位概念：「分布式事务」↔「Seata」</td>
 *       <td>对专有名词、缩写、版本号容易翻车（"Redis 7" 与 "Redis 6" 语义几乎相同）</td></tr>
 *   <tr><td><b>词法</b>（归一化包含 + 分词级 token 重叠）</td>
 *       <td>精确保住专有名词与缩写</td>
 *       <td>就是原实现的缺陷所在：同义换词全漏</td></tr>
 *   <tr><td><b>记忆固有信号</b>（{@link UserProfile.WeakPoint#priority()}）</td>
 *       <td>掌握度、顽固程度、复发次数、时效</td>
 *       <td>完全不看当前岗位，会把无关的顽固薄弱点顶上来</td></tr>
 * </table>
 * 三路各自产出一个排名，再用项目<b>已有但此前一直闲置</b>的 {@link RRFusion}（k=60）融合。
 * 选 RRF 而不是加权求和：三路的分值量纲完全不同（余弦 ∈[-1,1]、token 重叠是比例、
 * priority 是自定义合成分），直接加权需要人工调三个系数且难以解释；RRF 只用<b>排名</b>不用分值，
 * 天然免疫量纲差异，也是本项目题库多路召回已经验证过的做法。
 *
 * <h2>为什么不把薄弱点写进 Milvus</h2>
 * 这是本类最重要的一个工程取舍：<b>并非所有语义检索都需要向量库。</b>
 * 单个用户的候选薄弱点只有 10 条量级、JD 技能十几个，是「十几 × 十几」的相似度矩阵——
 * 一次批量 embedding + 内存点积即可，微秒级。反过来，把记忆条目写进 Milvus 有三个实际代价：
 * <ol>
 *   <li>会与题库共用同一个 collection，把两类语义完全不同的数据混在一起，污染题库检索；</li>
 *   <li>每次画像更新都要同步向量库，引入一致性问题（画像是高频小更新）；</li>
 *   <li>为 10 条数据付出建索引、加载 collection、网络往返的成本，纯属过度工程。</li>
 * </ol>
 *
 * <h2>Fail-open：召回是增强项，不是准入门槛</h2>
 * embedding 是唯一的外部调用，设了 {@link #EMBEDDING_TIMEOUT_MS} 超时预算；
 * 未配置 {@code EmbeddingModel}、调用异常或超时，一律降级为「词法 + 记忆」两路融合，
 * 并在结果里标记 {@link RecallResult#degraded}。这与项目既有的 fail-open 原则一致
 * （审题 Agent 挂了不能让面试没题问、评分失败给保守默认分继续）。
 */
@Slf4j
@Component
public class MemoryRecallService {

    /**
     * 语义相似度达到该阈值即认为与当前岗位相关。
     *
     * <h3>取值依据（由离线评估实测校准，非拍脑袋）</h3>
     * 最初取 0.55，是沿用「余弦 0.5 以上算相关」这种通用直觉。
     * 但在 40 条数据集上跑真实 embedding（{@code text-embedding-v3}）后发现
     * <b>阈值几乎永不触发</b>，语义通道贡献为 0。实测中文短技术词的余弦分布：
     * <table border="1">
     *   <tr><th>技能词 ↔ topic</th><th>余弦</th><th>是否应命中</th></tr>
     *   <tr><td>jmm ↔ JVM内存模型</td><td>0.5617</td><td>✅ 强相关</td></tr>
     *   <tr><td>日志采集 ↔ 可观测性建设</td><td>0.4266</td><td>✅ 上下位</td></tr>
     *   <tr><td>saga模式 ↔ 分布式事务</td><td>0.3533</td><td>✅ 同一考点</td></tr>
     *   <tr><td>saga模式 ↔ Java集合</td><td>0.4039</td><td>❌ 无关</td></tr>
     *   <tr><td>volatile ↔ Redis持久化</td><td>0.3695</td><td>❌ 无关</td></tr>
     * </table>
     *
     * <p><b>这张表暴露了一个必须承认的事实</b>：在 2~8 字的中文技术短语上，
     * 「相关」与「无关」的余弦区间<b>严重重叠</b>（0.35~0.56 vs 0.32~0.40），
     * 不存在一个能干净切分两者的阈值。因此这里的取值是一个明确的取舍：
     * <ul>
     *   <li>取 0.55 → 只有极强相关（jmm↔JMM）能命中，语义通道形同虚设；</li>
     *   <li>取 0.35 → 能救回 saga↔分布式事务，但同时放进大量无关项；</li>
     *   <li><b>取 0.42</b> → 覆盖「上下位/同义」这一档（0.42~0.56），
     *       放弃更弱的关联，把它们交由 RRF 的排名机制处理而不是二值判定。</li>
     * </ul>
     * 关键在于：<b>语义分值即使低于阈值，仍会作为一路排名参与 RRF 融合</b>，
     * 只是不再单独触发「强相关」标记。所以阈值调低的收益有限、风险却是直接的
     * （误判为强相关会稀释出题重点），故选择偏保守的 0.42 ——
     * 与记忆写入门控里「误并代价高于漏并」是同一判断方向。
     *
     * <p><b>可复现性说明</b>：该数值与具体 embedding 模型绑定。换模型后应重跑
     * {@code MemoryRecallSemanticEvalTest} 重新校准，而不是沿用此值。
     */
    public static final double SEMANTIC_RELEVANT_THRESHOLD = 0.42;

    /**
     * embedding 调用的超时预算（毫秒）。超时即降级，不拖慢出题主流程。
     *
     * <p>离线评估实测：40 个样本跑完约 18s，即单次批量 embedding（约 25 个短文本）
     * 平均 400~450ms，3s 预算约有 6 倍余量，够用。
     * 保持 3s 而不放宽，是因为它处在<b>出题前的关键路径</b>上——
     * 与其让用户多等，不如降级为词法 + 记忆两路（实测这两路已能拿到 0.7667 的 Recall@3）。
     */
    public static final long EMBEDDING_TIMEOUT_MS = 3000L;

    /**
     * 融合后判定为「强相关候选」的数量上限，即最终送进出题 Prompt 的条数。
     *
     * <p><b>必须小于上游候选池</b>（{@code LongTermMemory.WEAK_POINT_CANDIDATE_POOL = 30}），
     * 否则 RRF 就没有任何筛选空间。此前该值与 {@code WEAK_POINT_TOP_N} 同为 10，
     * 而上游也正好只给 10 条候选，导致融合是「10 进 10、一条都淘汰不掉」——
     * 三路 RRF 实际只完成了重排与分档，<b>并未起到筛选作用</b>。
     * 现在上游放宽到 30，这里保持 10，才构成真正的「召回 30 → 精排 10」。
     */
    private static final int FUSE_TOP_K = 10;

    private final RRFusion rrFusion;

    /**
     * 可选依赖：与项目既有的「可选工具模式」一致（未配置就自动退化，不让主流程变脆弱）。
     * 单测中传 null 即可走纯规则路径。
     */
    private final EmbeddingModel embeddingModel;

    /** embedding 调用专用小线程池，避免占用 graph 节点的 ForkJoinPool.commonPool。 */
    private final ExecutorService embeddingExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "memory-recall-embedding");
        t.setDaemon(true);
        return t;
    });

    public MemoryRecallService(RRFusion rrFusion,
                               @Autowired(required = false) EmbeddingModel embeddingModel) {
        this.rrFusion = rrFusion;
        this.embeddingModel = embeddingModel;
    }

    /** 召回结果：已按融合顺序排好，并区分「与当前岗位强相关」与「跨岗位供参考」。 */
    @Getter
    @Builder
    public static class RecallResult {
        /** 与当前岗位强相关的薄弱点（按融合排名有序）。 */
        private final List<UserProfile.WeakPoint> relevant;
        /** 其余薄弱点（跨岗位共性问题，仍保留供 LLM 参考）。 */
        private final List<UserProfile.WeakPoint> others;
        /** 是否降级（语义通道未参与）。 */
        private final boolean degraded;
        /** 实际生效的召回策略，用于日志与后续可观测。 */
        private final String strategy;

        public int total() {
            return relevant.size() + others.size();
        }
    }

    /**
     * 混合召回：把候选薄弱点按「与本场岗位的相关性 + 记忆固有优先级」重排并分档。
     *
     * @param candidates 候选薄弱点（来自 {@link LongTermMemory#getWeakPoints}，已过滤已掌握项）
     * @param jdSkills   当前 JD 的技能关键词（小写）
     * @param position   岗位名称，作为语义 query 的一部分
     */
    public RecallResult recall(List<UserProfile.WeakPoint> candidates,
                               List<String> jdSkills, String position) {
        if (candidates == null || candidates.isEmpty()) {
            return RecallResult.builder()
                    .relevant(List.of()).others(List.of())
                    .degraded(false).strategy("empty")
                    .build();
        }
        List<UserProfile.WeakPoint> valid = candidates.stream()
                .filter(Objects::nonNull)
                .filter(wp -> wp.getTopic() != null && !wp.getTopic().isBlank())
                .collect(Collectors.toList());
        if (valid.isEmpty()) {
            return RecallResult.builder()
                    .relevant(List.of()).others(List.of())
                    .degraded(false).strategy("empty")
                    .build();
        }

        // ===== 通道一：词法（归一化包含 + 分词 token 重叠）=====
        Map<String, Double> lexicalScores = new HashMap<>();
        for (UserProfile.WeakPoint wp : valid) {
            lexicalScores.put(wp.getTopic(), lexicalScore(wp, jdSkills));
        }

        // ===== 通道二：语义（可选，带超时预算）=====
        Map<String, Double> semanticScores = new HashMap<>();
        boolean degraded = true;
        if (embeddingModel != null && jdSkills != null && !jdSkills.isEmpty()) {
            try {
                Map<String, Double> sem = CompletableFuture
                        .supplyAsync(() -> semanticScores(valid, jdSkills, position), embeddingExecutor)
                        .get(EMBEDDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (sem != null && !sem.isEmpty()) {
                    semanticScores.putAll(sem);
                    degraded = false;
                }
            } catch (Exception e) {
                // fail-open：语义通道是增强项，失败不影响出题
                log.warn("[MemoryRecall] 语义召回不可用，降级为词法+记忆两路融合: {}",
                        e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }

        // ===== 三路各自排名，交给已有的 RRFusion 融合 =====
        List<List<RagDocument>> channels = new ArrayList<>();
        channels.add(rankByScore(valid, lexicalScores));
        if (!degraded) {
            channels.add(rankByScore(valid, semanticScores));
        }
        channels.add(rankBy(valid, Comparator.comparingDouble(UserProfile.WeakPoint::priority).reversed()));

        List<RagDocument> fused = rrFusion.fuse(channels, FUSE_TOP_K);

        // ===== 平局消解（确定性保证）=====
        // RRF 只看排名，当各路排名恰好互为镜像时会出现【完全平局】
        // （例：A 在词法路第 1、记忆路第 2，B 反之 → 两者 RRF 分数完全相同）。
        // 而 RRFusion 内部用 HashMap 聚合，平局项的相对顺序取决于哈希迭代顺序，
        // 意味着同样的输入可能产出不同的出题顺序 —— 这对「可复现」是不可接受的。
        // 因此这里补一层确定性次级排序：先按 RRF 分数，再按记忆固有优先级（更该考的靠前），
        // 最后按 topic 字典序兜底，保证同一输入永远得到同一结果。
        // 注：不去改 RRFusion 本身，因为它同时服务题库检索，改动面太大且非其职责。
        Map<String, UserProfile.WeakPoint> byTopic = valid.stream()
                .collect(Collectors.toMap(UserProfile.WeakPoint::getTopic, wp -> wp, (a, b) -> a,
                        LinkedHashMap::new));

        List<UserProfile.WeakPoint> ordered = fused.stream()
                .map(doc -> Map.entry(doc, byTopic.get(doc.getId())))
                .filter(e -> e.getValue() != null)
                .sorted(Comparator
                        .comparingDouble((Map.Entry<RagDocument, UserProfile.WeakPoint> e) -> rrfScore(e.getKey()))
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (Map.Entry<RagDocument, UserProfile.WeakPoint> e) -> e.getValue().priority()).reversed())
                        .thenComparing(e -> e.getValue().getTopic()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        // ===== 按融合顺序输出，并判定相关性分档 =====
        List<UserProfile.WeakPoint> relevant = new ArrayList<>();
        List<UserProfile.WeakPoint> others = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UserProfile.WeakPoint wp : ordered) {
            if (!seen.add(wp.getTopic())) {
                continue;
            }
            boolean lexHit = lexicalScores.getOrDefault(wp.getTopic(), 0.0) > 0;
            boolean semHit = semanticScores.getOrDefault(wp.getTopic(), 0.0) >= SEMANTIC_RELEVANT_THRESHOLD;
            // 任一通道判定相关即算强相关：词法保精确、语义补同义，两者是互补而非互斥关系
            if (lexHit || semHit) {
                relevant.add(wp);
            } else {
                others.add(wp);
            }
        }
        // 融合 TopK 之外的候选不丢弃，仍作为跨岗位参考附在最后（保持原有「不硬性过滤」的设计）
        for (UserProfile.WeakPoint wp : valid) {
            if (seen.add(wp.getTopic())) {
                others.add(wp);
            }
        }

        String strategy = degraded ? "lexical+memory(RRF)" : "semantic+lexical+memory(RRF)";
        log.info("[MemoryRecall] 召回完成：{} 条候选 → {} 强相关 / {} 供参考，策略={}",
                valid.size(), relevant.size(), others.size(), strategy);

        return RecallResult.builder()
                .relevant(relevant).others(others)
                .degraded(degraded).strategy(strategy)
                .build();
    }

    // ==================== 通道一：词法 ====================

    /**
     * 词法相关性得分，三级递降：
     * <ol>
     *   <li><b>同义词表归一后相等</b>（1.0）——把 JD 技能词与 topic 分别映射到规范名再比，
     *       让 {@code epoll ↔ IO模型}、{@code seata ↔ 分布式事务} 这类<b>字面零交集但确定等价</b>
     *       的关系在词法层就能命中；</li>
     *   <li><b>归一化字符串双向包含</b>（1.0）——保留原实现的精确匹配能力；</li>
     *   <li><b>分词级 token 重叠</b>（≤0.8）——覆盖「MySQL索引」vs「mysql 索引优化」这类局部重合。</li>
     * </ol>
     * 返回 0 表示词法上无关。
     *
     * <h3>⚠️ 第 ① 级是离线评估暴露出的第三个缺陷</h3>
     * 此前本方法只调 {@link MemoryWriteGate#normalizeRaw}（纯字符归一：大小写/空格/全角），
     * <b>从不调 {@link MemoryWriteGate#canonicalize}</b>（同义词表在后者里面）。
     * 后果是：{@code MemoryWriteGate} 里手工维护的 27 组同义词映射
     * <b>只在「写入」时生效，对「召回」毫无贡献</b>。实测证据（修复前）：
     * <table border="1">
     *   <tr><th>topic</th><th>JD 技能</th><th>表内映射</th><th>词法得分</th></tr>
     *   <tr><td>IO模型</td><td>epoll</td><td>✅ epoll→IO模型</td><td><b>0.000</b></td></tr>
     *   <tr><td>分布式事务</td><td>seata</td><td>✅ seata→分布式事务</td><td><b>0.000</b></td></tr>
     *   <tr><td>Go并发</td><td>goroutine</td><td>✅ goroutine→Go并发</td><td><b>0.000</b></td></tr>
     *   <tr><td>消息队列可靠性</td><td>幂等消费</td><td>✅ 幂等消费→…</td><td><b>0.000</b></td></tr>
     * </table>
     * 7 组抽样里 6 组得 0 —— 这些恰恰是「同义词表存在的全部理由」，却一条都没起作用。
     *
     * <p><b>为什么这个缺陷此前没被发现</b>：因为语义通道在这些 case 上碰巧也能命中，
     * 表面指标看不出问题。是在「语义通道被证明只在部分 case 有效」之后，
     * 才有必要追问「那降级路径为什么也漏」，从而暴露出来。
     * <b>这说明分层评估（降级路径单独跑）本身是有价值的 —— 它能定位问题出在哪一层。</b>
     *
     * <p><b>为什么放在最前而不是最后</b>：同义词表是<b>人工确认过的等价关系</b>，
     * 置信度高于任何自动计算（token 重叠、embedding 余弦），
     * 命中即给满分并短路后续计算，也顺带省去无谓的相似度运算。
     */
    double lexicalScore(UserProfile.WeakPoint wp, List<String> jdSkills) {
        if (jdSkills == null || jdSkills.isEmpty()) {
            return 0.0;
        }
        // 把 topic 与它的别名一起参与匹配：别名是实体归一时留档的原始写法，
        // 它们往往更接近 JD 里的用词。
        List<String> surfaces = new ArrayList<>();
        surfaces.add(wp.getTopic());
        if (wp.getAliases() != null) {
            surfaces.addAll(wp.getAliases());
        }

        double best = 0.0;
        for (String surface : surfaces) {
            if (surface == null || surface.isBlank()) {
                continue;
            }
            String topicKey = MemoryWriteGate.normalizeRaw(surface);
            // topic 侧也走一次同义词归一：画像里的 topic 虽已在写入时归一过，
            // 但历史数据（改造前写入的）可能是未归一的原始写法。
            String topicCanon = MemoryWriteGate.normalizeRaw(
                    MemoryWriteGate.canonicalize(surface, List.of()));
            Set<String> topicTokens = tokenSet(surface);
            for (String skill : jdSkills) {
                if (skill == null || skill.isBlank()) {
                    continue;
                }
                String skillKey = MemoryWriteGate.normalizeRaw(skill);
                if (skillKey.isEmpty() || topicKey.isEmpty()) {
                    continue;
                }

                // ① 同义词表归一后相等：人工确认的等价关系，置信度最高。
                //    注意 canonicalize 的第二个参数刻意传空集合——这样它【只走同义词表精确命中】，
                //    不会执行 bigram 近似合并（那一步依赖 existingTopics）。
                //    原因：近似合并的 0.75 阈值是为「写入时防分裂」调的，
                //    误并的代价在写入侧是「永久失去独立追踪」、在召回侧则是「误判为强相关」，
                //    两者容忍度不同。召回这里只要确定等价的部分，模糊的交给 token 重叠与语义通道。
                String skillCanon = MemoryWriteGate.normalizeRaw(
                        MemoryWriteGate.canonicalize(skill, List.of()));
                if (!skillCanon.isEmpty() && skillCanon.equals(topicCanon)) {
                    return 1.0;   // 短路：已是最高分，无需再算
                }

                // ② 双向包含：最强的字面信号
                if (topicKey.contains(skillKey) || skillKey.contains(topicKey)) {
                    best = Math.max(best, 1.0);
                    continue;
                }
                // ③ token 重叠比例：局部重合也给分
                Set<String> skillTokens = tokenSet(skill);
                if (skillTokens.isEmpty() || topicTokens.isEmpty()) {
                    continue;
                }
                Set<String> inter = new HashSet<>(topicTokens);
                inter.retainAll(skillTokens);
                // ⚠️ 单个 CJK 字重叠不算相关：离线评估暴露出的真实假阳性——
                // 「操作系统内存管理」与「缓存穿透」仅共有一个「存」字，
                // 按比例算得 1/min(8,4)×0.8 = 0.2 > 0 就被判为强相关，属明显误召回。
                // 中文单字的语义太弱（存/理/性/化/务 等在技术词汇里高频出现），
                // 因此要求：要么有多字重叠，要么重叠的是一个英文词（如 mysql / redis）。
                if (!isMeaningfulOverlap(inter)) {
                    continue;
                }
                if (!inter.isEmpty()) {
                    double overlap = (double) inter.size()
                            / Math.min(topicTokens.size(), skillTokens.size());
                    best = Math.max(best, overlap * 0.8);
                }
            }
        }
        return best;
    }

    /**
     * 判定 token 重叠是否构成有意义的相关性信号。
     *
     * <p>单个中文字的语义太弱——「存」「理」「性」「化」「务」「统」这类字在技术词汇里高频出现，
     * 仅凭一个共同汉字就判相关会产生大量假阳性（离线评估中「操作系统内存管理」因一个「存」字
     * 被误判为与「缓存穿透」相关）。因此要求满足其一：
     * <ul>
     *   <li>重叠 token 数 ≥ 2（多字重合，如「索引」「事务」）；</li>
     *   <li>重叠中包含长度 ≥ 2 的 ASCII 词（如 {@code mysql} / {@code redis} / {@code kafka}），
     *       英文技术词的区分度远高于单个汉字。</li>
     * </ul>
     * 这条规则遵循与 {@code MemoryWriteGate} 合并阈值一致的原则：
     * <b>宁可漏判交给语义通道兜底，也不要在词法层引入误判。</b>
     */
    private static boolean isMeaningfulOverlap(Set<String> intersection) {
        if (intersection.isEmpty()) {
            return false;
        }
        if (intersection.size() >= 2) {
            return true;
        }
        String only = intersection.iterator().next();
        return only.length() >= 2 && only.chars().allMatch(c -> c < 128);
    }

    /** 轻量分词：按非字母数字切分，中文按单字切（避免为 10 条数据引入完整 BM25 索引）。 */
    private Set<String> tokenSet(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder buf = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                if (c < 128) {
                    buf.append(c);           // 英文/数字连续成词
                } else {
                    if (buf.length() > 0) {
                        tokens.add(buf.toString());
                        buf.setLength(0);
                    }
                    tokens.add(String.valueOf(c));  // CJK 按单字
                }
            } else if (buf.length() > 0) {
                tokens.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            tokens.add(buf.toString());
        }
        return tokens;
    }

    // ==================== 通道二：语义 ====================

    /**
     * 批量 embedding + 内存余弦相似度。
     *
     * <h3>为什么是「逐技能词取最大值」而不是把技能栈拼成一句话</h3>
     * 最初的实现把岗位与全部技能拼成一段 query
     * （{@code "Java后端开发工程师 岗位技术要求：saga模式、xa协议"}）再与 topic 比，
     * 上线离线评估后发现<b>语义排序整个是反的</b>——实测数据：
     * <table border="1">
     *   <tr><th>方案</th><th>分布式事务（应命中）</th><th>Java集合（无关）</th></tr>
     *   <tr><td>拼句 query</td><td>0.1858</td><td><b>0.3623</b>（更高！）</td></tr>
     *   <tr><td>逐词取最大</td><td><b>0.3533</b></td><td>0.4039</td></tr>
     * </table>
     * 原因有两个，都指向同一个本质：
     * <ol>
     *   <li><b>岗位前缀是强噪声</b>。{@code "Java后端开发工程师"} 这类通用职称占据了句向量
     *       的主要语义分量，导致 query 向量偏向「岗位描述」这个方向，而不是具体技术点。
     *       于是同样含「Java」字样的 {@code Java集合} 反而更接近 —— 它匹配的是职称，不是考点。
     *       这与题库检索的情形恰好相反：那边 query 是一整句自然语言问题，句向量是合适的粒度。</li>
     *   <li><b>多技能拼接会互相稀释</b>。把 {@code saga模式} 与 {@code xa协议} 拼在一起后，
     *       句向量落在两者的「平均语义」处，反而离任何单个技术点都更远。而实际语义是
     *       <b>析取（OR）</b>关系：只要 topic 与<b>任一</b>技能强相关就该召回，不是与「技能栈整体」相关。</li>
     * </ol>
     * 因此改为：每个技能词单独 embedding，与 topic 逐一算余弦后<b>取最大值</b>，
     * 与词法通道的 {@code best = Math.max(...)} 保持同一析取语义。
     *
     * <h3>成本没有变差</h3>
     * 仍是<b>一次</b>批量调用：{@code embed(N个技能 + M个topic)}，只是把「1+M」变成「N+M」，
     * 相似度计算在内存里做 N×M 次点积（N≈15、M≈10，微秒级）。
     * 不需要为此引入向量库 —— 这也是当初刻意不把薄弱点写进 Milvus 的原因之一。
     *
     * <h3>岗位信息去哪了</h3>
     * 岗位不再进入 query，因为实测证明它是噪声而非上下文。
     * 「同一个『性能优化』在前端与后端语义不同」这个担忧是真实的，
     * 但它由<b>候选集本身</b>解决：候选薄弱点来自该用户的历史画像，
     * 而 JD 技能栈已经限定了领域，不需要再靠职称字符串提供上下文。
     */
    private Map<String, Double> semanticScores(List<UserProfile.WeakPoint> candidates,
                                               List<String> jdSkills, String position) {
        // 去重并过滤空白技能词，减少无效 embedding
        List<String> skills = jdSkills.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        if (skills.isEmpty()) {
            return Map.of();
        }

        List<String> texts = new ArrayList<>(skills);
        for (UserProfile.WeakPoint wp : candidates) {
            texts.add(wp.getTopic());
        }

        List<float[]> vectors = embeddingModel.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            return Map.of();
        }

        int n = skills.size();
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            float[] topicVec = vectors.get(n + i);
            double best = 0.0;
            for (int j = 0; j < n; j++) {
                best = Math.max(best, cosine(vectors.get(j), topicVec));
            }
            scores.put(candidates.get(i).getTopic(), best);
        }
        return scores;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ==================== 排名 → RagDocument（复用 RRFusion）====================

    /**
     * 把一路打分结果转成 {@link RRFusion} 需要的有序文档列表。
     * <p>用 topic 作为文档 id——RRF 只依赖「同一 id 在各路中的排名」，
     * 因此把 {@code WeakPoint} 轻量包装成 {@code RagDocument} 就能直接复用已有融合器，
     * 不必为记忆场景再写一个 RRF。得分为 0 的项不进入该路排名（避免无关项白占名次）。
     */
    private List<RagDocument> rankByScore(List<UserProfile.WeakPoint> candidates,
                                          Map<String, Double> scores) {
        return candidates.stream()
                .filter(wp -> scores.getOrDefault(wp.getTopic(), 0.0) > 0)
                .sorted(Comparator.comparingDouble(
                        (UserProfile.WeakPoint wp) -> scores.getOrDefault(wp.getTopic(), 0.0)).reversed())
                .map(this::toDoc)
                .collect(Collectors.toList());
    }

    private List<RagDocument> rankBy(List<UserProfile.WeakPoint> candidates,
                                     Comparator<UserProfile.WeakPoint> comparator) {
        return candidates.stream().sorted(comparator).map(this::toDoc).collect(Collectors.toList());
    }

    /**
     * 取出 {@link RRFusion} 写入 metadata 的融合分数（key 为 {@code _rrf_score}）。
     * 取不到时返回 0，使该项落到排序末尾而不是抛异常。
     */
    private static double rrfScore(RagDocument doc) {
        if (doc == null || doc.getMetadata() == null) {
            return 0.0;
        }
        Object v = doc.getMetadata().get("_rrf_score");
        return (v instanceof Number n) ? n.doubleValue() : 0.0;
    }

    private RagDocument toDoc(UserProfile.WeakPoint wp) {
        return RagDocument.builder()
                .id(wp.getTopic())
                .content(wp.getTopic())
                .metadata(new HashMap<>())
                .build();
    }
}
