/**
 */
package com.interview.agent.agent;

import com.interview.agent.model.PlannedQuestion;
import com.interview.agent.model.QuestionDefect;
import com.interview.agent.model.QuestionDirection;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4：出题 Agent 与审题 Agent 之间的【规则预检层】——纯 Java、零 token、确定性判定。
 * <p>
 * 设计动机：出题草稿里的缺陷分两类。一类是"机械可判定"的硬错误——难度标注和方向要求不一致、
 * follow_ups 为空、题干过短、两个方向的题干文本高度重叠。这类问题用几十行代码就能确定性判断，
 * 既不需要花模型的钱，也不存在误判；另一类是"必须理解语义"才能判断的（考点跑偏、难度名不副实、
 * 追问不递进、事实存疑），才值得交给审题 Agent。
 * <p>
 * 把前者从 Critic 的 prompt 里剥离出来有三个好处：
 * <ol>
 *   <li>省 token：每档 5 道题，硬错误占了打回理由的相当一部分，这部分完全不需要模型参与；</li>
 *   <li>零误判：LLM 判断"两道题是否重复"会有随机性，而归一化后的字符重叠率是确定的；</li>
 *   <li>Critic 的 prompt 能专注在真正需要语义理解的维度上，指令更短、注意力更集中。</li>
 * </ol>
 * 与 {@code ReviewPlanner} 里 B2 反思循环的 {@code findUncoveredHighPriorityAreas} 是同一套
 * "规则自检 → 缺口回喂模型"思路，这里把它推广成了一个可独立测试的组件。
 */
@Slf4j
public final class QuestionRuleChecker {

    /**
     * 题干近重复判定阈值：归一化后的 bigram Jaccard 相似度超过此值即认为两个方向出了实质相同的题。
     * <p>
     * 取 0.6 是一个刻意偏保守的经验值——规则层只负责"几乎肯定重复"的情况（最典型的是同一道
     * 题库原题被两个邻近方向同时检索命中，比如"MySQL索引优化"与"MySQL B+树"）。
     * <p>
     * <b>为什么不调低阈值去多抓一些</b>：实测"MySQL索引失效的场景有哪些"与"有哪些场景会导致
     * MySQL索引失效"这类同义换序的相似度只有约 0.5，调低到能抓住它，就会同时开始误伤考点相邻
     * 但确实不同的题目。而误判的代价比漏判更高——合格的题被白白打回重出，既烧 token 又可能
     * 越改越差。因此这类"措辞不同、考点相同"的语义重复<b>故意留给审题 Agent</b>处理，
     * 那是它擅长而规则层做不到的事。分层的意义正在于此：规则层要零误判，Critic 负责理解语义。
     */
    private static final double DUPLICATE_THRESHOLD = 0.6;

    /** 题干最短长度：短于此长度基本不可能是一道完整的面试题（多为模型输出被截断） */
    private static final int MIN_CONTENT_LENGTH = 8;

    private QuestionRuleChecker() {
    }

    /**
     * 对一档草稿做规则预检。
     *
     * @param dirs   本档的全部方向（下标即 direction_index）
     * @param drafts 出题 Agent 产出的草稿，key 为 direction_index（可能不全，缺的方向不在此校验，
     *               由调用方按"未覆盖"逻辑单独降级处理）
     * @return 缺陷列表，空列表代表规则层无异议
     */
    public static List<QuestionDefect> check(List<QuestionDirection> dirs,
                                             Map<Integer, PlannedQuestion> drafts) {
        List<QuestionDefect> defects = new ArrayList<>();
        if (dirs == null || drafts == null || drafts.isEmpty()) {
            return defects;
        }

        // ===== 逐题校验 =====
        for (Map.Entry<Integer, PlannedQuestion> e : drafts.entrySet()) {
            int idx = e.getKey();
            PlannedQuestion q = e.getValue();
            if (q == null || idx < 0 || idx >= dirs.size()) {
                continue;
            }
            QuestionDirection dir = dirs.get(idx);

            String content = q.getContent() == null ? "" : q.getContent().trim();
            if (content.isEmpty()) {
                defects.add(QuestionDefect.ofRule(idx, "题干为空"));
            } else if (content.length() < MIN_CONTENT_LENGTH) {
                defects.add(QuestionDefect.ofRule(idx,
                        "题干过短（仅 " + content.length() + " 字），疑似输出被截断，请重新给出完整题目"));
            }

            // 难度必须与方向给定的完全一致：StageScheduler 依赖难度分档抽题，标错会直接破坏
            // 动态难度调节（adjustDifficulty）的梯度效果
            if (dir.getDifficulty() != null
                    && !dir.getDifficulty().equalsIgnoreCase(q.getDifficulty())) {
                defects.add(QuestionDefect.ofRule(idx, String.format(
                        "difficulty 标注为 \"%s\"，但该方向要求的难度是 \"%s\"，必须保持一致",
                        q.getDifficulty(), dir.getDifficulty())));
            }

            if (q.getFollowUps() == null || q.getFollowUps().isEmpty()) {
                defects.add(QuestionDefect.ofRule(idx, "缺少 follow_ups（至少需要 1 个追问）"));
            }

            // ===== 按 type 差异化校验 =====
            // experience 类必须有 context（简历上下文），design 类追问至少 2 条（需要更深入的追问链）
            String type = dir.getType();
            if ("experience".equalsIgnoreCase(type)) {
                if (dir.getContext() == null || dir.getContext().trim().isEmpty()) {
                    defects.add(QuestionDefect.ofRule(idx,
                            "experience 类方向缺少 context 字段（必须携带简历上下文，禁止杜撰简历中不存在的经历）"));
                }
            }
            if ("design".equalsIgnoreCase(type)) {
                int followUpCount = (q.getFollowUps() == null) ? 0 : q.getFollowUps().size();
                if (followUpCount < 2) {
                    defects.add(QuestionDefect.ofRule(idx,
                            "design 类方向 follow_ups 至少需要 2 条（设计题需要更深入的追问链考察架构取舍）"));
                }
            }

            if (q.getReference() == null || q.getReference().trim().isEmpty()) {
                defects.add(QuestionDefect.ofRule(idx, "缺少 reference 参考答案要点"));
            }

            if (q.getSource() == null || q.getSource().trim().isEmpty()) {
                defects.add(QuestionDefect.ofRule(idx,
                        "缺少 source 字段（题库原题ID / llm / web:<链接> 三者之一）"));
            }
        }

        // ===== 跨方向题干近重复校验 =====
        // 逐方向独立检索的天然副作用：邻近方向很容易命中同一道或高度相似的题库原题。
        // 出题 Agent 的注意力在"逐个方向查库"，不会主动回头做全局比对，所以这一步必须在
        // 组装完成后统一做。
        List<Integer> indices = new ArrayList<>(drafts.keySet());
        indices.sort(null);
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < indices.size(); i++) {
            for (int j = i + 1; j < indices.size(); j++) {
                int a = indices.get(i);
                int b = indices.get(j);
                PlannedQuestion qa = drafts.get(a);
                PlannedQuestion qb = drafts.get(b);
                if (qa == null || qb == null) {
                    continue;
                }
                double sim = bigramJaccard(normalize(qa.getContent()), normalize(qb.getContent()));
                if (sim < DUPLICATE_THRESHOLD) {
                    continue;
                }
                String key = a + "-" + b;
                if (!reported.add(key)) {
                    continue;
                }
                // 只打回后一个方向：前一个保留，避免两道题互相打回后双双被重出（浪费且可能一起变差）
                defects.add(QuestionDefect.ofRule(b, String.format(
                        "题干与方向 direction_index=%d 的题目高度重复（文本相似度约 %.0f%%），"
                                + "请针对本方向的考点重新出一道区分度明显的题", a, sim * 100)));
            }
        }

        return defects;
    }

    /** 归一化：去掉空白与标点、统一小写，只保留可比较的实义字符 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}]+", "");
    }

    /**
     * 基于字符 bigram 的 Jaccard 相似度。
     * <p>
     * 选 bigram 而不是整串相等或编辑距离：中文题干里"MySQL 索引失效的场景有哪些"和
     * "哪些场景会导致 MySQL 索引失效"是同一道题的不同措辞，整串比对判不出来，而 bigram 集合
     * 重叠度能稳定捕捉这种同义换序；同时它对长度差异不敏感，比编辑距离更适合判"实质重复"。
     */
    private static double bigramJaccard(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }
}
