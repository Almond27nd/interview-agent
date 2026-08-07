/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.interview.agent.memory.MemoryRecallService;
import com.interview.agent.rag.RRFusion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 记忆召回的<b>完整三路</b>离线评估（含语义通道）。
 *
 * <h2>为什么要单独一个测试类</h2>
 * {@link MemoryRecallEvaluatorTest} 传 {@code null} 作为 {@code EmbeddingModel}，
 * 因此语义通道<b>从未被执行</b>，跑出的是「词法 + 记忆」两路的降级成绩。
 * 那份成绩的价值在于<b>零依赖、可复现、可进 CI</b>——不发网络请求，
 * 结果不会因为 embedding 模型版本变化而漂移。
 *
 * <p>但代价是：M3 立项的核心理由（补同义词与上下位概念）恰恰没有被量化。
 * 例如「分布式事务」与 JD 的 {@code saga模式 / xa协议} 字面零交集、且未收录进同义词表，
 * 只有语义通道能救回。因此本类补上真实 embedding 调用的一跑。
 *
 * <h2>为什么用 Assumption 而不是 @Disabled</h2>
 * 本测试需要真实 API Key。用 {@link org.junit.jupiter.api.Assumptions} 在缺少 Key 时
 * <b>跳过而非失败</b>，这样：
 * <ul>
 *   <li>他人克隆仓库后 {@code mvn test} 依然全绿，不会因为没有 Key 而红；</li>
 *   <li>有 Key 的环境自动获得完整三路的数字，无需额外命令。</li>
 * </ul>
 * 这与项目里 {@code WebSearchTool} 的「未配置 key 则不注册 Bean、Agent 自动退化」
 * 是同一种可选依赖处理思路。
 *
 * <h2>Key 的读取顺序</h2>
 * 环境变量 {@code DASHSCOPE_API_KEY} → 项目根目录 {@code .env} 文件。
 * 读 {@code .env} 是因为项目本身就用它管理密钥，而 Maven surefire 不会自动加载它。
 */
class MemoryRecallSemanticEvalTest {

    private static String apiKey;

    @BeforeAll
    static void loadKey() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readFromDotEnv();
        }
    }

    /** 从项目根目录 .env 读取 key（surefire 不会自动加载 .env）。 */
    private static String readFromDotEnv() {
        Path env = Path.of(".env");
        if (!Files.exists(env)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("DASHSCOPE_API_KEY=")) {
                    String v = s.substring("DASHSCOPE_API_KEY=".length()).trim();
                    // 去掉可能存在的引号
                    v = v.replaceAll("^[\"']|[\"']$", "");
                    return v.isBlank() ? null : v;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static EmbeddingModel realEmbeddingModel() {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        return new DashScopeEmbeddingModel(api, MetadataMode.EMBED);
    }

    @Test
    @DisplayName("三路完整召回 vs 两路降级：量化语义通道的独立贡献")
    void evaluate_semanticChannelContribution() {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 DASHSCOPE_API_KEY（环境变量或 .env），跳过语义通道评估");

        List<MemoryEvalSample> dataset = MemoryEvalDatasetTemplate.build();

        // ===== 两路（降级）=====
        MemoryRecallEvaluator.Report degraded =
                new MemoryRecallEvaluator(new MemoryRecallService(new RRFusion(), null))
                        .evaluate(dataset);

        // ===== 三路（含语义）=====
        MemoryRecallEvaluator.Report full =
                new MemoryRecallEvaluator(new MemoryRecallService(new RRFusion(), realEmbeddingModel()))
                        .evaluate(dataset);

        MemoryRecallEvaluator.StrategyMetrics base = full.getBaseline();
        MemoryRecallEvaluator.StrategyMetrics two = degraded.getHybrid();
        MemoryRecallEvaluator.StrategyMetrics three = full.getHybrid();

        System.out.println();
        System.out.println("================ 语义通道贡献度对照 ================");
        System.out.printf("样本数: %d    三路中实际降级样本: %d%n",
                dataset.size(), three.getDegradedCount());
        System.out.println("---------------------------------------------------");
        System.out.printf("%-22s %9s %9s %9s %9s%n", "策略", "R@1", "R@3", "MRR", "Prec");
        System.out.printf("%-22s %9.4f %9.4f %9.4f %9.4f%n", "baseline(改造前)",
                base.getRecallAt1(), base.getRecallAt3(), base.getMrr(), base.getPrecision());
        System.out.printf("%-22s %9.4f %9.4f %9.4f %9.4f%n", "hybrid 两路(降级)",
                two.getRecallAt1(), two.getRecallAt3(), two.getMrr(), two.getPrecision());
        System.out.printf("%-22s %9.4f %9.4f %9.4f %9.4f%n", "hybrid 三路(含语义)",
                three.getRecallAt1(), three.getRecallAt3(), three.getMrr(), three.getPrecision());
        System.out.println("---------------------------------------------------");
        System.out.printf("%-22s %+9.4f %+9.4f %+9.4f %+9.4f%n", "语义通道独立贡献",
                three.getRecallAt1() - two.getRecallAt1(),
                three.getRecallAt3() - two.getRecallAt3(),
                three.getMrr() - two.getMrr(),
                three.getPrecision() - two.getPrecision());
        System.out.printf("%-22s %+9.4f %+9.4f %+9.4f %+9.4f%n", "三路 vs baseline 总计",
                three.getRecallAt1() - base.getRecallAt1(),
                three.getRecallAt3() - base.getRecallAt3(),
                three.getMrr() - base.getMrr(),
                three.getPrecision() - base.getPrecision());
        System.out.println("===================================================");

        // 逐样本对照：哪些样本是语义通道单独救回的
        System.out.println("\n---- 语义通道单独救回的样本 ----");
        Map<String, MemoryRecallEvaluator.SampleComparison> byId2 = degraded.getComparisons().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MemoryRecallEvaluator.SampleComparison::getSampleId, c -> c));
        int semanticOnlyWins = 0;
        for (MemoryRecallEvaluator.SampleComparison c3 : full.getComparisons()) {
            MemoryRecallEvaluator.SampleComparison c2 = byId2.get(c3.getSampleId());
            if (c2 == null) {
                continue;
            }
            if (c3.hybridF1() > c2.hybridF1()) {
                semanticOnlyWins++;
                System.out.printf("  · %s (%s)  F1 %.2f→%.2f%n     两路召回: %s%n     三路召回: %s%n     应召回: %s%n",
                        c3.getSampleId(), c3.getPosition(),
                        c2.hybridF1(), c3.hybridF1(),
                        c2.getHybridRecalled(), c3.getHybridRecalled(), c3.getRelevantTopics());
            }
        }
        System.out.printf("---- 共 %d 条样本因语义通道而改善 ----%n", semanticOnlyWins);

        // ===== 断言 =====
        // ① 语义通道必须真的跑起来了（不能因为超时/异常又静默降级）
        assertTrue(three.getDegradedCount() < dataset.size(),
                "语义通道未生效（全部降级），检查 API Key 与网络。降级数=" + three.getDegradedCount());

        // ② 三路的 Recall 不应低于两路。
        //    注意这里刻意只断言 Recall 而非 Precision——语义通道天然会引入一些
        //    「语义相近但考点不同」的召回（如 Redis 6 vs Redis 7），Precision 可能小幅下降，
        //    这属已知取舍，不应让测试失败。
        assertTrue(three.getRecallAt3() >= two.getRecallAt3() - 1e-9,
                String.format("三路 Recall@3(%.4f) 不应低于两路(%.4f)",
                        three.getRecallAt3(), two.getRecallAt3()));

        // ③ 三路必须优于 baseline（这是 M3 存在的根本理由）
        assertTrue(three.getRecallAt3() > base.getRecallAt3(),
                "三路混合召回应显著优于改造前的 contains 基线");
    }

    @Test
    @DisplayName("语义通道应救回同义词表未覆盖的纯语义样本（mem_009 / mem_018 / mem_038）")
    void evaluate_rescuesPureSemanticSamples() {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 DASHSCOPE_API_KEY，跳过");

        MemoryRecallEvaluator.Report full =
                new MemoryRecallEvaluator(new MemoryRecallService(new RRFusion(), realEmbeddingModel()))
                        .evaluate(MemoryEvalDatasetTemplate.build());

        // 这三条在降级路径下 Recall 全为 0，是「只有语义能救」的典型：
        //   mem_009: JD=saga模式/xa协议        → 应召回「分布式事务」
        //   mem_018: JD=jmm/volatile/cas       → 应召回「JVM内存模型」「Java并发」
        //   mem_038: JD=日志采集/elk/监控告警   → 应召回「可观测性建设」
        List<String> pureSemantic = List.of("mem_009", "mem_018", "mem_038");
        int rescued = 0;
        System.out.println("\n---- 纯语义样本的召回情况 ----");
        for (String id : pureSemantic) {
            MemoryRecallEvaluator.SampleComparison c = full.getComparisons().stream()
                    .filter(x -> id.equals(x.getSampleId()))
                    .findFirst().orElseThrow();
            boolean ok = c.getHybridRecallAt3() > 0;
            if (ok) {
                rescued++;
            }
            System.out.printf("  %s %s  R@3=%.2f  召回=%s  应召回=%s%n",
                    ok ? "[救回]" : "[仍漏]", id, c.getHybridRecallAt3(),
                    c.getHybridRecalled(), c.getRelevantTopics());
        }
        System.out.printf("---- %d/%d 条纯语义样本被救回 ----%n", rescued, pureSemantic.size());

        // 只要求至少救回一条：embedding 对中文短技术词的表现有波动，
        // 断言过严会让测试变成「模型质量监控」而非「链路正确性验证」。
        assertTrue(rescued >= 1,
                "语义通道至少应救回 1 条纯语义样本，实际 " + rescued + " 条");
    }
}
