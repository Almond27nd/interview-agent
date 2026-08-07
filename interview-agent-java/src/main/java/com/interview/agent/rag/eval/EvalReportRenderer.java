/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag.eval;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 评估报告渲染与落盘（与 Go 版本 eval_report.go 一致）。
 * 输出 JSON（机器可读，A/B 对比）+ Markdown（人可读摘要）两种格式。
 */
public final class EvalReportRenderer {

    private EvalReportRenderer() {
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void saveReportJson(EvalReport report, Path filePath) throws IOException {
        Files.writeString(filePath, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
    }

    public static void saveReportMarkdown(EvalReport report, Path filePath) throws IOException {
        Files.writeString(filePath, renderReportMarkdown(report), StandardCharsets.UTF_8);
    }

    /**
     * 将 EvalReport 渲染为可读的 Markdown 摘要。
     * 包含：基本信息、配置快照、整体指标、按 topic 分组、按 difficulty 分组、Worst-10。
     */
    public static String renderReportMarkdown(EvalReport report) {
        StringBuilder b = new StringBuilder();
        renderHeader(b, report);
        renderConfig(b, report);
        renderOverall(b, report);
        renderTopicMetrics(b, report);
        renderDifficultyMetrics(b, report);
        renderWorstSamples(b, report);
        return b.toString();
    }

    private static void renderHeader(StringBuilder b, EvalReport r) {
        b.append("# RAG 离线评估报告\n\n");
        if (r.getRunAt() != null) {
            b.append(String.format("- **运行时间**：%s%n", r.getRunAt().format(TIME_FMT)));
        }
        b.append(String.format("- **数据集版本**：%s%n", r.getDatasetVersion()));
        if (r.getDatasetPath() != null && !r.getDatasetPath().isEmpty()) {
            b.append(String.format("- **数据集路径**：`%s`%n", r.getDatasetPath()));
        }
        b.append(String.format("- **样本数**：%d%n", r.getSampleCount()));
        b.append(String.format("- **耗时**：%s%n%n", r.getDuration()));
    }

    private static void renderConfig(StringBuilder b, EvalReport r) {
        b.append("## 1. 配置快照\n\n| 字段 | 值 |\n|------|----|\n");
        RagConfigSnapshot c = r.getConfig();
        if (c != null) {
            if (notBlank(c.getEmbeddingModel())) b.append(String.format("| EmbeddingModel | %s |%n", c.getEmbeddingModel()));
            if (c.getVectorDim() > 0) b.append(String.format("| VectorDim | %d |%n", c.getVectorDim()));
            if (c.getVectorTopK() > 0) b.append(String.format("| VectorTopK | %d |%n", c.getVectorTopK()));
            if (c.getBm25TopK() > 0) b.append(String.format("| BM25TopK | %d |%n", c.getBm25TopK()));
            if (c.getBm25K1() > 0) b.append(String.format("| BM25 k1 | %.2f |%n", c.getBm25K1()));
            if (c.getBm25B() > 0) b.append(String.format("| BM25 b | %.2f |%n", c.getBm25B()));
            if (notBlank(c.getRerankerType())) b.append(String.format("| RerankerType | %s |%n", c.getRerankerType()));
            if (c.getRerankTopN() > 0) b.append(String.format("| RerankTopN | %d |%n", c.getRerankTopN()));
            if (notBlank(c.getNote())) b.append(String.format("| Note | %s |%n", c.getNote()));
        }
        b.append("\n");
    }

    private static void renderOverall(StringBuilder b, EvalReport r) {
        b.append("## 2. 整体指标\n\n| 指标 | 值 |\n|------|----|\n");
        MetricsSummary o = r.getOverall() != null ? r.getOverall() : MetricsSummary.builder().build();
        b.append(String.format("| Recall@10 | %.4f |%n", o.getRecallAt10()));
        b.append(String.format("| Recall@20 | %.4f |%n", o.getRecallAt20()));
        b.append(String.format("| MRR       | %.4f |%n%n", o.getMrr()));
    }

    private static void renderTopicMetrics(StringBuilder b, EvalReport r) {
        if (r.getTopicMetrics() == null || r.getTopicMetrics().isEmpty()) {
            return;
        }
        b.append("## 3. 按 Topic 分组\n\n| Topic | Count | Recall@10 | Recall@20 | MRR |\n|-------|-------|-----------|-----------|-----|\n");
        List<String> topics = new ArrayList<>(r.getTopicMetrics().keySet());
        Collections.sort(topics);
        for (String t : topics) {
            MetricsSummary m = r.getTopicMetrics().get(t);
            b.append(String.format("| %s | %d | %.4f | %.4f | %.4f |%n",
                    t, m.getCount(), m.getRecallAt10(), m.getRecallAt20(), m.getMrr()));
        }
        b.append("\n");
    }

    private static void renderDifficultyMetrics(StringBuilder b, EvalReport r) {
        if (r.getDifficultyMetrics() == null || r.getDifficultyMetrics().isEmpty()) {
            return;
        }
        b.append("## 4. 按难度分组\n\n| Difficulty | Count | Recall@10 | Recall@20 | MRR |\n|------------|-------|-----------|-----------|-----|\n");
        for (String d : List.of("easy", "medium", "hard")) {
            MetricsSummary m = r.getDifficultyMetrics().get(d);
            if (m != null) {
                b.append(String.format("| %s | %d | %.4f | %.4f | %.4f |%n",
                        d, m.getCount(), m.getRecallAt10(), m.getRecallAt20(), m.getMrr()));
            }
        }
        b.append("\n");
    }

    private static void renderWorstSamples(StringBuilder b, EvalReport r) {
        if (r.getWorstSamples() == null || r.getWorstSamples().isEmpty()) {
            return;
        }
        b.append("## 5. 异常样本（Worst-10）\n\n> 按 Recall@10 升序排列，便于针对性优化检索策略。\n\n");
        b.append("| SampleID | Query | Topic | Recall@10 | MRR | FirstHitRank |\n|----------|-------|-------|-----------|-----|--------------|\n");
        for (SampleResult s : r.getWorstSamples()) {
            String query = s.getQuery() != null ? s.getQuery() : "";
            if (query.length() > 40) {
                query = query.substring(0, 40) + "...";
            }
            query = query.replace("|", "\\|").replace("\n", " ");
            String rank = s.getFirstHitRank() > 0 ? String.valueOf(s.getFirstHitRank()) : "-";
            b.append(String.format("| %s | %s | %s | %.4f | %.4f | %s |%n",
                    s.getSampleId(), query, s.getTopic() != null ? s.getTopic() : "",
                    s.getRecallAt10(), s.getMrr(), rank));
        }
        b.append("\n");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
