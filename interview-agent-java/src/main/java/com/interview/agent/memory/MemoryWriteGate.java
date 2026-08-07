/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆写入门控（Write Gate）—— 长期记忆的「入口质检」。
 *
 * <p><b>为什么需要它</b>：业界记忆系统的第一条工程原则是「写入门控比向量库更重要」——
 * 脏数据一旦入库，后面所有检索、排序、Prompt 注入全部被污染，再好的召回也救不回来。
 * 本项目此前是「无条件全量盲写」，造成两个具体问题：
 *
 * <ol>
 *   <li><b>伪证据入库</b>：限时答题超时会记 0 分，而这个 0 分被无条件写成薄弱点证据。
 *       但超时反映的是「候选人不在电脑前」（接电话、合盖、断网），<b>不代表能力</b>；
 *       又因为 0 分是最低分，它会排在薄弱点最前面，直接把下一场面试的出题方向带偏。</li>
 *   <li><b>topic 碎片化</b>：薄弱点的 topic 直接取题目的 {@code skills} 标签，而该标签是
 *       题库标注或 LLM 自由生成的文本。于是「MySQL索引」「MySQL 索引优化」「索引失效」
 *       会成为三个独立薄弱点，把 {@code WEAK_POINT_TOP_N=10} 的配额挤满，
 *       真正多样的薄弱点被挤出去。</li>
 * </ol>
 *
 * <p><b>分层设计（与出题审查环节同一套方法论）</b>：
 * <table border="1">
 *   <tr><th></th><th>出题审查</th><th>记忆写入（本类）</th></tr>
 *   <tr><td>规则层</td><td>{@code QuestionRuleChecker}：字段缺失、难度不一致、bigram 近重复</td>
 *       <td>大小写/空格/全半角归一 + 同义词表 + bigram 近似合并</td></tr>
 *   <tr><td>语义层</td><td>{@code QuestionReviewer}（Critic）</td>
 *       <td>由调用方在规则未命中时可选接入 embedding 相似度（见 {@code LongTermMemory}）</td></tr>
 * </table>
 * 原则一致：<b>机械可判定的用规则（零 token、零误判），需要理解语义的才交给模型。</b>
 *
 * <p>本类是 {@code final} 静态工具类（与 {@code QuestionRuleChecker} 一致），无状态、无 IO，
 * 可独立单元测试。
 */
@Slf4j
public final class MemoryWriteGate {

    private MemoryWriteGate() {
    }

    /** 低于该分数视为答错（与 {@code LongTermMemory} 的薄弱点判定阈值保持一致）。 */
    public static final double WRONG_SCORE_THRESHOLD = 60.0;

    /**
     * 规则层同义词表：把常见的等价写法映射到规范 topic。
     * 只放「机械可判定」的高频同义，语义近似交给语义通道，避免规则层无限膨胀。
     *
     * <h3>它同时服务写入与召回</h3>
     * <ul>
     *   <li><b>写入侧</b>（{@link #canonicalize}）：防止「MySQL索引 / mysql 索引优化 / 索引失效」
     *       分裂成三条薄弱点，挤满召回配额；</li>
     *   <li><b>召回侧</b>（{@code MemoryRecallService.lexicalScore} 第①级）：
     *       让 {@code epoll ↔ IO模型} 这类<b>字面零交集但确定等价</b>的关系在词法层就能命中。</li>
     * </ul>
     * <b>注意</b>：召回侧接入是后补的。此前 {@code lexicalScore} 只调 {@link #normalizeRaw}
     * （纯字符归一），导致本表<b>只在写入时生效、对召回毫无贡献</b>——
     * 抽样 7 组表内映射，6 组词法得分为 0。这个缺陷由离线评估的分层对比暴露出来
     * （先单独跑降级路径、再对比三路，才让「降级路径为什么也漏」成为必须回答的问题）。
     *
     * <h3>⚠️ 为什么刻意<b>不</b>按评估结论扩充这张表</h3>
     * 曾按「运维 SRE / 大数据 / 测试开发三个领域指标偏低」这个评估结论扩表，
     * 补入 {@code docker}、{@code 日志采集}、{@code checkpoint}、{@code olap}、{@code saga模式} 等词，
     * 指标随即从 Recall@3 = 0.6194 飙到 0.8083。但这些词<b>全部来自评估样本的 jd_skills</b>——
     * 本质是 <b>train/test 泄漏</b>：用测试集的答案构造规则，再在同一测试集上宣称提升。
     * 三个反常信号同时出现：反自证统计里「两策略均失败」从 15 条骤降到 4 条、
     * 语义通道独立贡献由正转负、运维 SRE 从 0.1667 跳到满分 1.0000。
     * 实测泄漏率达 53.8%，故已回滚。
     *
     * <p><b>由此确立的维护原则</b>：
     * <ul>
     *   <li>本表只收录<b>确有把握且与评估集无关</b>的高频同义写法；</li>
     *   <li><b>禁止</b>因「某领域评估指标偏低」而把该领域评估样本里的词抄进来——
     *       那样提升的是记忆力，不是能力；</li>
     *   <li>领域覆盖不足应通过<b>扩充评估数据集</b>暴露真实水平，
     *       或交由语义通道处理，而非扩表刷分；</li>
     *   <li>泄漏率由 {@code LeakageReport} 持续度量，并有测试卡住上限。</li>
     * </ul>
     *
     * <p>更根本的原因是：<b>规则型组件天生容易泄漏</b>——
     * 「加一条规则」和「记住一个答案」在实现上是同一个动作。
     * 而模型型组件不容易（无法为让某条样本通过而手改权重）。
     * 所以规则表的每一次扩充都必须自问：这条是普适知识，还是在背题。
     */
    private static final Map<String, String> CANONICAL_ALIASES = new LinkedHashMap<>();

    static {
        // MySQL / 数据库
        putAll("MySQL索引", "mysql索引优化", "索引优化", "索引失效", "mysql索引失效", "数据库索引", "mysql 索引");
        putAll("MySQL事务", "mysql事务隔离", "事务隔离级别", "数据库事务", "mysql 事务");
        putAll("MySQL锁", "mysql 锁机制", "行锁", "间隙锁", "innodb锁");
        putAll("分库分表", "水平分表", "垂直分库", "sharding");
        // Redis
        putAll("Redis持久化", "rdb", "aof", "redis rdb aof");
        putAll("Redis缓存问题", "缓存穿透", "缓存击穿", "缓存雪崩");
        putAll("Redis数据结构", "redis 数据类型", "redis底层数据结构");
        // JVM / Java
        putAll("JVM内存模型", "jmm", "java内存模型", "内存区域划分");
        putAll("JVM垃圾回收", "gc", "垃圾回收", "gc调优", "jvm调优", "垃圾收集器");
        putAll("Java并发", "并发编程", "多线程", "juc", "线程安全");
        putAll("线程池", "threadpoolexecutor", "java线程池");
        putAll("Java集合", "集合框架", "hashmap原理", "concurrenthashmap");
        // Go
        putAll("Go并发", "goroutine", "go协程", "gmp", "go调度器");
        putAll("Go channel", "channel原理", "go管道");
        putAll("Go内存管理", "go gc", "go垃圾回收");
        // 分布式
        putAll("分布式事务", "两阶段提交", "2pc", "3pc", "tcc", "seata", "最终一致性");
        putAll("分布式锁", "redlock", "分布式互斥");
        putAll("分布式共识", "raft", "paxos", "一致性算法");
        putAll("服务治理", "熔断", "限流", "降级", "熔断降级");
        // 消息队列
        putAll("消息队列可靠性", "消息丢失", "消息重复消费", "幂等消费", "消息可靠投递");
        putAll("Kafka原理", "kafka", "kafka分区", "kafka副本");
        // 网络 / 操作系统
        putAll("TCP协议", "tcp", "三次握手", "四次挥手", "tcp可靠传输");
        putAll("HTTP协议", "http", "https", "http2");
        putAll("IO模型", "io多路复用", "epoll", "select poll epoll", "nio");
        putAll("操作系统内存管理", "虚拟内存", "分页机制", "页面置换");
        // 架构
        putAll("系统设计", "架构设计", "高并发设计", "高可用设计");
        putAll("微服务", "微服务架构", "服务拆分");
    }

    private static void putAll(String canonical, String... aliases) {
        CANONICAL_ALIASES.put(normalizeRaw(canonical), canonical);
        for (String alias : aliases) {
            CANONICAL_ALIASES.put(normalizeRaw(alias), canonical);
        }
    }

    /**
     * 判定一条「本题得分」是否可以作为能力证据写入长期记忆。
     *
     * @param timedOut 本题是否超时未作答
     * @param answer   用户答案（空答案同样不构成能力证据）
     * @return true 表示证据可信、允许写入
     */
    public static boolean acceptAsEvidence(boolean timedOut, String answer) {
        if (timedOut) {
            // 超时 = 人不在电脑前，不是能力信号。这是原实现最典型的脏数据来源。
            return false;
        }
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String trimmed = answer.trim();
        // 明确的「跳过/不会」类占位回答也不作为能力证据（它们不携带可评估的作答内容）
        return !"[超时未作答]".equals(trimmed);
    }

    /**
     * 基础归一：去首尾空白、压缩内部空白、全角转半角、统一小写，用于做 key 比较。
     * 注意返回的是「比较用的 key」，不是展示用的规范名。
     *
     * <p>用 {@code Locale.ROOT} 而非默认 locale 做小写化：土耳其语等 locale 下
     * {@code "I".toLowerCase()} 会得到无点 {@code ı}，导致同一 topic 在不同环境归一出不同 key。
     *
     * <p><b>可见性说明</b>：本方法已由三处跨包调用方共同印证其通用性——
     * 写入侧（{@link #canonicalize}）、召回侧（{@code MemoryRecallService.lexicalScore}）、
     * 以及出题方向去重（{@code DirectionQuotaChecker}）。「同一考点的不同写法应归一到同一 key」
     * 是全项目共享的语义，因此对外公开，避免各处重复实现出细微不一致的归一规则。
     */
    public static String normalizeRaw(String topic) {
        if (topic == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(topic.length());
        for (char c : topic.toCharArray()) {
            // 全角字符转半角
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-/]+", "")
                .replaceAll("[（）()【】\\[\\]，,。.、；;：:]+", "")
                .trim();
    }

    /**
     * 规则层归一：把自由文本 topic 映射为规范 topic。
     * <p>三步走：① 同义词表精确命中；② 与既有 topic 的归一化 key 完全相同则复用既有；
     * ③ 与既有 topic 的字符 bigram Jaccard 相似度超阈值则合并到既有。
     * 三步都不中则返回清理后的原文（新建 canonical topic）。
     *
     * @param rawTopic       原始 topic（题目 skills 标签）
     * @param existingTopics 该用户画像中已存在的 topic 列表
     * @return 规范化后的 topic
     */
    public static String canonicalize(String rawTopic, Collection<String> existingTopics) {
        if (rawTopic == null || rawTopic.isBlank()) {
            return "";
        }
        String cleaned = rawTopic.trim().replaceAll("\\s+", " ");
        String key = normalizeRaw(cleaned);
        if (key.isEmpty()) {
            return cleaned;
        }

        // ① 同义词表精确命中
        String mapped = CANONICAL_ALIASES.get(key);
        if (mapped != null) {
            return mapped;
        }

        if (existingTopics == null || existingTopics.isEmpty()) {
            return cleaned;
        }

        // ② 与既有 topic 归一化后完全相同 → 复用既有写法（避免仅因空格/大小写差异分裂）
        for (String existing : existingTopics) {
            if (existing != null && normalizeRaw(existing).equals(key)) {
                return existing;
            }
        }

        // ③ bigram Jaccard 近似合并（复用出题审查环节验证过的判重手法）
        String best = null;
        double bestSim = 0.0;
        for (String existing : existingTopics) {
            if (existing == null || existing.isBlank()) {
                continue;
            }
            double sim = bigramJaccard(key, normalizeRaw(existing));
            if (sim > bestSim) {
                bestSim = sim;
                best = existing;
            }
        }
        if (best != null && bestSim >= MERGE_THRESHOLD) {
            return best;
        }
        return cleaned;
    }

    /**
     * topic 合并阈值。
     *
     * <p><b>刻意取偏高（0.75）</b>：topic 通常很短（2~8 个字），短文本的 bigram 相似度天然偏高，
     * 阈值低会把「MySQL索引」和「MySQL事务」这类<b>前缀相同但考点完全不同</b>的 topic 误并成一条。
     * 误并的代价比漏并高得多——两个不同知识点被合并后，其中一个会<b>永久失去独立追踪能力</b>，
     * 而漏并只是多占一个 Top N 名额、下次仍有机会被语义层合并。
     *
     * <p>与出题审查环节的判重阈值一样，这里遵循同一条原则：
     * <b>规则层要零误判，把把握不准的留给上层语义判断。</b>
     */
    public static final double MERGE_THRESHOLD = 0.75;

    /** 字符 bigram 的 Jaccard 相似度（与 {@code QuestionRuleChecker} 同一手法）。 */
    static double bigramJaccard(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        List<String> ga = bigrams(a);
        List<String> gb = bigrams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> sa = new java.util.HashSet<>(ga);
        java.util.Set<String> sb = new java.util.HashSet<>(gb);
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private static List<String> bigrams(String s) {
        List<String> result = new ArrayList<>();
        if (s.length() == 1) {
            result.add(s);
            return result;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            result.add(s.substring(i, i + 2));
        }
        return result;
    }
}
