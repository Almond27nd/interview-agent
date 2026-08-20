/**
 */
package com.interview.agent.memory.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 记忆召回评估的命令行入口。
 *
 * <p>用法：
 * <pre>
 *   # 生成示例数据集模板（首次使用）
 *   mvn spring-boot:run -Dspring-boot.run.arguments="memeval --gen-template"
 *
 *   # 跑评估（默认读 data/eval/memory_dataset_v1.json）
 *   mvn spring-boot:run -Dspring-boot.run.arguments="memeval"
 *
 *   # 指定数据集与输出目录
 *   mvn spring-boot:run -Dspring-boot.run.arguments="memeval --dataset path/to/ds.json --out data/eval/reports"
 * </pre>
 *
 * <p>刻意用独立的 {@code memeval} 子命令而非并入已有的 {@code eval}：
 * 两者评的是完全不同的东西（题库检索 vs 记忆召回），数据集格式与指标口径都不同，
 * 混在一个命令里会让参数语义变得含混。
 *
 * <p>非 {@code memeval} 启动时该 Runner 直接返回，不影响 Web 服务。
 */
@Slf4j
@Component
@Order(2)
public class MemoryEvalCommandRunner implements CommandLineRunner {

    private static final String DEFAULT_DATASET = "data/eval/memory_dataset_v1.json";
    private static final String DEFAULT_OUT_DIR = "data/eval/reports";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final MemoryRecallEvaluator evaluator;

    public MemoryEvalCommandRunner(MemoryRecallEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !"memeval".equals(args[0])) {
            return;
        }
        int code = 0;
        try {
            String dataset = DEFAULT_DATASET;
            String outDir = DEFAULT_OUT_DIR;
            boolean genTemplate = false;

            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--gen-template" -> genTemplate = true;
                    case "--dataset" -> {
                        if (i + 1 < args.length) dataset = args[++i];
                    }
                    case "--out" -> {
                        if (i + 1 < args.length) outDir = args[++i];
                    }
                    default -> log.warn("[MemEval] 未识别参数: {}", args[i]);
                }
            }

            if (genTemplate) {
                writeTemplate(dataset);
            } else {
                runEvaluate(dataset, outDir);
            }
        } catch (Exception e) {
            log.error("[MemEval] 执行失败: {}", e.getMessage(), e);
            code = 1;
        }
        System.exit(code);
    }

    private void runEvaluate(String datasetPath, String outDir) throws IOException {
        Path dsPath = Path.of(datasetPath);
        if (!Files.exists(dsPath)) {
            throw new IllegalStateException("数据集不存在: " + datasetPath
                    + "\n请先运行: memeval --gen-template 生成模板后按需标注");
        }
        List<MemoryEvalSample> dataset = MAPPER.readValue(
                Files.readString(dsPath, StandardCharsets.UTF_8),
                new TypeReference<List<MemoryEvalSample>>() {});
        System.out.printf("[MemEval] 已加载 %d 条样本: %s%n", dataset.size(), datasetPath);

        MemoryRecallEvaluator.Report report = evaluator.evaluate(dataset);

        Files.createDirectories(Path.of(outDir));
        String ts = report.getRunAt().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path jsonPath = Path.of(outDir, "memory_eval_" + ts + ".json");
        Path mdPath = Path.of(outDir, "memory_eval_" + ts + ".md");

        Files.writeString(jsonPath, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(mdPath, MemoryEvalReportRenderer.render(report), StandardCharsets.UTF_8);

        // 控制台直接打核心结论，不用翻文件
        MemoryRecallEvaluator.StrategyMetrics base = report.getBaseline();
        MemoryRecallEvaluator.StrategyMetrics hyb = report.getHybrid();
        System.out.println();
        System.out.println("========== 记忆召回评估结果 ==========");
        System.out.printf("样本数: %d    耗时: %s    降级样本: %d%n",
                report.getSampleCount(), report.getDuration(), hyb.getDegradedCount());
        System.out.println("--------------------------------------");
        System.out.printf("%-12s %10s %10s %10s %10s %10s%n",
                "策略", "Recall@1", "Recall@3", "MRR", "Precision", "F1");
        System.out.printf("%-12s %10.4f %10.4f %10.4f %10.4f %10.4f%n", "baseline",
                base.getRecallAt1(), base.getRecallAt3(), base.getMrr(),
                base.getPrecision(), base.getF1());
        System.out.printf("%-12s %10.4f %10.4f %10.4f %10.4f %10.4f%n", "hybrid",
                hyb.getRecallAt1(), hyb.getRecallAt3(), hyb.getMrr(),
                hyb.getPrecision(), hyb.getF1());
        System.out.printf("%-12s %+10.4f %+10.4f %+10.4f %+10.4f %+10.4f%n", "提升",
                hyb.getRecallAt1() - base.getRecallAt1(),
                hyb.getRecallAt3() - base.getRecallAt3(),
                hyb.getMrr() - base.getMrr(),
                hyb.getPrecision() - base.getPrecision(),
                hyb.getF1() - base.getF1());
        System.out.println("--------------------------------------");
        if (report.getRescuedTopicCounts() != null && !report.getRescuedTopicCounts().isEmpty()) {
            System.out.println("被 hybrid 救回的薄弱点（baseline 漏召回）:");
            report.getRescuedTopicCounts().forEach((t, c) ->
                    System.out.printf("  · %s (×%d)%n", t, c));
        }
        System.out.println("======================================");
        System.out.printf("报告已输出:%n  %s%n  %s%n", mdPath, jsonPath);
    }

    /**
     * 生成示例数据集模板。
     * <p>刻意内置 6 条覆盖典型场景的样本，让首次运行就能看到 baseline 与 hybrid 的差异，
     * 而不是给一个空数组让人无从下手。使用者可在此基础上按真实岗位增补。
     */
    private void writeTemplate(String datasetPath) throws IOException {
        List<MemoryEvalSample> template = MemoryEvalDatasetTemplate.build();
        Path p = Path.of(datasetPath);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(p, MAPPER.writeValueAsString(template), StandardCharsets.UTF_8);
        System.out.printf("[MemEval] 已生成 %d 条示例样本: %s%n", template.size(), datasetPath);
        System.out.println("请按真实岗位与画像增补标注后运行: memeval");
    }
}
