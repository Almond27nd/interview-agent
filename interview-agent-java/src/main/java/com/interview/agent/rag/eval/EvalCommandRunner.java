/**
 */
package com.interview.agent.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.config.AppConfig;
import com.interview.agent.loader.DocumentLoader;
import com.interview.agent.loader.QuestionParser;
import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RAG 离线评估 CLI（与 Go 版本 cmd/eval.go 一致）。
 *
 * <p>用法：以子命令 {@code eval} 启动应用，例如：
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments="eval --prepare"
 *   mvn spring-boot:run -Dspring-boot.run.arguments="eval --gen-dataset"
 *   mvn spring-boot:run -Dspring-boot.run.arguments="eval --note baseline"
 *   java -jar interview-agent.jar eval --note baseline --skip-rerank
 * </pre>
 *
 * 三种模式：
 * <ul>
 *   <li>{@code --prepare}      解析 MD 题库 → 写入 Milvus → 导出 manifest.json</li>
 *   <li>{@code --gen-dataset}  基于 manifest 自动生成评估数据集</li>
 *   <li>默认                    加载 dataset → 检索 → 计算指标 → 输出报告（JSON + Markdown）</li>
 * </ul>
 *
 * 非 {@code eval} 启动时该 Runner 直接返回，不影响 Web 服务。
 */
@Slf4j
@Component
@Order(1)
public class EvalCommandRunner implements CommandLineRunner {

    /** 评估专用 userID，和业务用户隔离 */
    private static final String EVAL_USER_ID = "eval_user";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    private final QuestionParser questionParser;
    private final DocumentLoader documentLoader;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final RetrievalEvaluator retrievalEvaluator;
    private final AppConfig appConfig;

    @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v3}")
    private String embeddingModel;

    public EvalCommandRunner(QuestionParser questionParser, DocumentLoader documentLoader,
                             MilvusStore milvusStore, BM25Manager bm25Manager,
                             RetrievalEvaluator retrievalEvaluator, AppConfig appConfig) {
        this.questionParser = questionParser;
        this.documentLoader = documentLoader;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
        this.retrievalEvaluator = retrievalEvaluator;
        this.appConfig = appConfig;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !"eval".equals(args[0])) {
            return; // 非 eval 子命令：正常 Web 启动，不介入
        }

        Args a = parseArgs(Arrays.copyOfRange(args, 1, args.length));
        int code = 0;
        try {
            if (a.prepare) {
                runPrepare(a.questionsDir, a.manifestPath);
            } else if (a.genDataset) {
                runGenDataset(a.manifestPath, a.datasetPath, a.sampleCount);
            } else {
                runExecute(a.datasetPath, a.outDir, a.note, a.skipRerank, a.manifestPath);
            }
        } catch (Exception e) {
            log.error("[Eval] 执行失败: {}", e.getMessage(), e);
            code = 1;
        }
        // CLI 子命令执行完即退出，不保持 Web 服务运行
        System.exit(code);
    }

    // ============================================================
    // prepare 模式：解析 MD 题库 → 写入 Milvus/BM25 → 导出 manifest
    // ============================================================

    private void runPrepare(String questionsDir, String manifestPath) throws IOException {
        // 1. 扫描 MD 文件（questionsDir/*/*.md）
        List<Path> mdFiles = scanMarkdownFiles(questionsDir);
        if (mdFiles.isEmpty()) {
            throw new IllegalStateException("[Prepare] 未找到 MD 题库文件，请确认 " + questionsDir + " 目录结构正确（<topic>_interview/<name>.md）");
        }
        System.out.printf("[Prepare] 找到 %d 个 MD 题库文件%n", mdFiles.size());

        // 2. 清除旧的 eval_user 数据
        System.out.println("[Prepare] 清除 eval_user 旧数据...");
        try {
            milvusStore.deleteByUserID(EVAL_USER_ID);
        } catch (Exception e) {
            log.warn("[Prepare] 清除旧数据失败（可能无旧数据）: {}", e.getMessage());
        }

        // 3. 逐文件解析 + 写入
        List<ManifestEntry> allEntries = new ArrayList<>();
        Map<String, Integer> topicCounter = new LinkedHashMap<>();

        for (Path mdFile : mdFiles) {
            String baseName = mdFile.getFileName().toString();
            String dirName = mdFile.getParent().getFileName().toString();
            String topicPrefix = dirName.endsWith("_interview")
                    ? dirName.substring(0, dirName.length() - "_interview".length())
                    : dirName;

            System.out.printf("%n[Prepare] 解析: %s%n", mdFile);

            String rawText;
            try {
                rawText = documentLoader.loadFile(mdFile.toString());
            } catch (Exception e) {
                log.warn("[Prepare] 读取 {} 失败: {}，跳过", mdFile, e.getMessage());
                continue;
            }
            System.out.printf("[Prepare] 文件长度: %d 字符%n", rawText.length());

            System.out.println("[Prepare] LLM 解析中...");
            QuestionParser.ParseResult result = questionParser.parseQuestionBank(rawText);
            System.out.printf("[Prepare] 解析完成: 识别 %d 道，通过 %d 道，失败 %d 道%n",
                    result.getTotal(), result.getSuccess(), result.getFailed());

            if (result.getSuccess() == 0 || result.getQuestions().isEmpty()) {
                continue;
            }

            List<MilvusStore.ParsedQuestionInput> milvusQuestions = new ArrayList<>();
            for (QuestionParser.ParsedQuestion q : result.getQuestions()) {
                int n = topicCounter.merge(topicPrefix, 1, Integer::sum);
                String stableID = String.format("eval_%s_%03d", topicPrefix, n);

                milvusQuestions.add(MilvusStore.ParsedQuestionInput.builder()
                        .id(stableID)
                        .content(q.getContent())
                        .reference(q.getReference())
                        .type(q.getType())
                        .difficulty(q.getDifficulty())
                        .skills(q.getSkills())
                        .build());

                String preview = q.getContent();
                if (preview != null && preview.length() > 80) {
                    preview = preview.substring(0, 80) + "...";
                }
                allEntries.add(ManifestEntry.builder()
                        .id(stableID)
                        .contentPreview(preview)
                        .content(q.getContent())
                        .reference(q.getReference())
                        .topic(topicPrefix)
                        .difficulty(q.getDifficulty())
                        .type(q.getType())
                        .skills(q.getSkills())
                        .sourceFile(baseName)
                        .build());
            }

            try {
                milvusStore.loadParsedQuestions(EVAL_USER_ID, baseName, milvusQuestions);
                System.out.printf("[Prepare] ✓ %s: %d 道题写入 Milvus%n", baseName, milvusQuestions.size());
            } catch (Exception e) {
                log.warn("[Prepare] 写入 Milvus 失败 ({}): {}", mdFile, e.getMessage());
            }
        }

        if (allEntries.isEmpty()) {
            throw new IllegalStateException("[Prepare] 没有成功解析任何题目，请检查 MD 文件和 LLM 配置");
        }

        // 4. 导出 manifest.json
        writeJson(Path.of(manifestPath), allEntries);

        System.out.printf("%n======== Prepare 完成 ========%n");
        System.out.printf("题目总数:   %d%n", allEntries.size());
        topicCounter.forEach((topic, count) -> System.out.printf("  %s: %d 道%n", topic, count));
        System.out.printf("Manifest:   %s%n", manifestPath);
        System.out.printf("%n下一步：基于 manifest 中的 ID 标注评估数据集，或运行 eval --gen-dataset 自动生成%n");
    }

    // ============================================================
    // gen-dataset 模式：基于 manifest 自动生成评估数据集
    // ============================================================

    private void runGenDataset(String manifestPath, String datasetPath, int targetCount) throws IOException {
        List<ManifestEntry> entries = readManifest(manifestPath);
        if (entries.isEmpty()) {
            throw new IllegalStateException("[GenDataset] manifest 为空，请先运行 eval --prepare");
        }

        // 按 topic 分组
        Map<String, List<ManifestEntry>> topicGroups = new HashMap<>();
        for (ManifestEntry e : entries) {
            topicGroups.computeIfAbsent(e.getTopic(), k -> new ArrayList<>()).add(e);
        }
        List<String> topics = new ArrayList<>(topicGroups.keySet());
        Collections.sort(topics);

        int perTopic = Math.max(targetCount / Math.max(topics.size(), 1), 3);

        List<EvalSample> samples = new ArrayList<>();
        int sampleIdx = 0;

        for (String topic : topics) {
            List<ManifestEntry> group = topicGroups.get(topic);
            String displayName = topicDisplayName(topic);

            int step = Math.max(group.size() / perTopic, 1);
            int count = 0;
            for (int i = 0; i < group.size() && count < perTopic; i += step) {
                ManifestEntry entry = group.get(i);
                sampleIdx++;

                String difficulty = (entry.getDifficulty() == null || entry.getDifficulty().isEmpty())
                        ? "medium" : entry.getDifficulty();

                samples.add(EvalSample.builder()
                        .id(String.format("eval_%03d", sampleIdx))
                        .query(generateQueryFromEntry(entry))
                        .relevantDocIds(findRelatedEntries(entry, group))
                        .topic(displayName)
                        .difficulty(difficulty)
                        .note("自动生成，种子题: " + entry.getId())
                        .build());
                count++;
            }
        }

        writeJson(Path.of(datasetPath), samples);

        System.out.printf("%n======== 评估数据集生成完成 ========%n");
        System.out.printf("样本总数:   %d%n", samples.size());
        for (String t : topics) {
            String displayName = topicDisplayName(t);
            long c = samples.stream().filter(s -> displayName.equals(s.getTopic())).count();
            System.out.printf("  %s: %d 条%n", displayName, c);
        }
        System.out.printf("输出路径:   %s%n", datasetPath);
        System.out.printf("%n下一步：运行 eval --note baseline 执行评估%n");
    }

    // ============================================================
    // eval 默认模式：加载数据集 → 检索 → 计算指标 → 输出报告
    // ============================================================

    private void runExecute(String datasetPath, String outDir, String note,
                            boolean skipRerank, String manifestPath) throws IOException {
        // 1. 加载评估数据集
        List<EvalSample> samples = MAPPER.readValue(
                Files.readString(Path.of(datasetPath), StandardCharsets.UTF_8),
                new TypeReference<>() {
                });
        System.out.printf("[Eval] 数据集加载完成: %s (样本数: %d)%n", datasetPath, samples.size());

        // 2. 从 manifest 加载 BM25 索引（不依赖本地 JSON 题库文件）
        loadBm25FromManifest(manifestPath);
        System.out.printf("[Eval] BM25 索引就绪 (userID=%s)%n", EVAL_USER_ID);

        String rerankerType = skipRerank ? "none" : retrievalEvaluator.activeRerankerType();
        if (skipRerank) {
            System.out.println("[Eval] 已跳过 Reranker");
        }

        // 3. 构造 RAGConfig 快照
        RagConfigSnapshot ragCfg = RagConfigSnapshot.builder()
                .embeddingModel(embeddingModel)
                .vectorDim(MilvusStore.VECTOR_DIMENSION)
                .vectorTopK(RetrievalEvaluator.RETRIEVE_TOP_K)
                .bm25TopK(20)
                .bm25K1(1.5)
                .bm25B(0.75)
                .rerankerType(rerankerType)
                .rerankTopN(RetrievalEvaluator.RETRIEVE_TOP_K)
                .note(note)
                .build();

        // 4. 跑评估
        System.out.println("[Eval] 开始评估...");
        EvalReport report = retrievalEvaluator.runEvaluation(samples, EVAL_USER_ID, ragCfg, !skipRerank);
        report.setDatasetPath(datasetPath);

        // 5. 输出报告（JSON + Markdown）
        Path outDirPath = Path.of(outDir);
        Files.createDirectories(outDirPath);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path jsonPath = outDirPath.resolve("eval_report_" + timestamp + ".json");
        Path mdPath = outDirPath.resolve("eval_report_" + timestamp + ".md");

        EvalReportRenderer.saveReportJson(report, jsonPath);
        EvalReportRenderer.saveReportMarkdown(report, mdPath);

        System.out.println();
        System.out.println("======== 评估完成 ========");
        System.out.printf("样本数:     %d%n", report.getSampleCount());
        System.out.printf("耗时:       %s%n", report.getDuration());
        System.out.printf("Recall@10:  %.4f%n", report.getOverall().getRecallAt10());
        System.out.printf("Recall@20:  %.4f%n", report.getOverall().getRecallAt20());
        System.out.printf("MRR:        %.4f%n", report.getOverall().getMrr());
        System.out.println();
        System.out.printf("JSON 报告:     %s%n", jsonPath);
        System.out.printf("Markdown 报告: %s%n", mdPath);
    }

    // ============================================================
    // 辅助：数据集自动生成（与 Go 一致）
    // ============================================================

    /** 从 manifest 条目生成 SearchQuery 风格的查询（关键词组合，不是完整问句）。 */
    static String generateQueryFromEntry(ManifestEntry entry) {
        String content = entry.getContent() == null ? "" : entry.getContent();

        String[] prefixes = {
                "请详细描述", "请详细解释", "请详细说明",
                "请描述一下", "请解释一下", "请说明一下",
                "请描述", "请解释", "请说明", "请简述", "请列举",
                "详细描述", "详细解释", "详细说明",
                "简述", "描述", "解释", "说明",
        };
        for (String p : prefixes) {
            if (content.startsWith(p)) {
                content = content.substring(p.length());
                break;
            }
        }

        // 标点替换为空格
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            switch (c) {
                case '？', '?', '。', '！', '!', '（', '）', '(', ')', '、', '，', ',', '；', ';', '：', ':', '\n', '\r' ->
                        sb.append(' ');
                default -> sb.append(c);
            }
        }

        // 合并多余空格
        content = Arrays.stream(sb.toString().trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));

        // 截断到合理长度（35 个字符）
        if (content.length() > 35) {
            content = content.substring(0, 35);
        }
        return content.trim();
    }

    /** 找出和种子题相关的文档 ID：种子题本身 + 同 topic 中 skill 重叠 ≥2 的题目（最多 2 个）。 */
    static List<String> findRelatedEntries(ManifestEntry seed, List<ManifestEntry> group) {
        List<String> result = new ArrayList<>();
        result.add(seed.getId());

        if (seed.getSkills() == null || seed.getSkills().size() < 2) {
            return result;
        }

        Set<String> seedSkills = seed.getSkills().stream()
                .map(s -> s.trim().toLowerCase())
                .collect(Collectors.toSet());

        record Candidate(String id, int overlap) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ManifestEntry e : group) {
            if (e.getId().equals(seed.getId()) || e.getSkills() == null) {
                continue;
            }
            int overlap = 0;
            for (String s : e.getSkills()) {
                if (seedSkills.contains(s.trim().toLowerCase())) {
                    overlap++;
                }
            }
            if (overlap >= 2) {
                candidates.add(new Candidate(e.getId(), overlap));
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.overlap(), a.overlap()));
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            result.add(candidates.get(i).id());
        }
        return result;
    }

    /** 将 manifest 中的 topic 前缀转为可读名称。 */
    static String topicDisplayName(String topic) {
        return switch (topic) {
            case "go" -> "Go";
            case "mysql" -> "MySQL";
            case "redis" -> "Redis";
            case "distributed" -> "分布式系统";
            case "mq" -> "消息队列";
            default -> topic;
        };
    }

    // ============================================================
    // 辅助：manifest / BM25 / 文件
    // ============================================================

    /** 从 manifest.json 加载题目到 BM25 索引（content + reference，和 Milvus 写入格式一致）。 */
    private void loadBm25FromManifest(String manifestPath) {
        List<ManifestEntry> entries;
        try {
            entries = readManifest(manifestPath);
        } catch (IOException e) {
            log.warn("[BM25] 读取 manifest 失败: {}，请先运行 eval --prepare 生成 manifest.json", e.getMessage());
            return;
        }
        if (entries.isEmpty()) {
            log.warn("[BM25] manifest 为空，BM25 检索将不可用");
            return;
        }
        List<RagDocument> docs = entries.stream().map(e -> {
            String content = e.getContent();
            if (e.getReference() != null && !e.getReference().isEmpty()) {
                content = content + "\n参考答案：" + e.getReference();
            }
            return RagDocument.builder().id(e.getId()).content(content).build();
        }).collect(Collectors.toList());

        bm25Manager.replaceDocuments(EVAL_USER_ID, docs);
        log.info("[BM25] 从 manifest 加载完成: {} 篇文档 (userID={})", docs.size(), EVAL_USER_ID);
    }

    private List<ManifestEntry> readManifest(String manifestPath) throws IOException {
        String data = Files.readString(Path.of(manifestPath), StandardCharsets.UTF_8);
        return MAPPER.readValue(data, new TypeReference<>() {
        });
    }

    private static List<Path> scanMarkdownFiles(String questionsDir) throws IOException {
        Path root = Path.of(questionsDir);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static void writeJson(Path path, Object value) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, MAPPER.writeValueAsString(value), StandardCharsets.UTF_8);
    }

    // ============================================================
    // 辅助：参数解析
    // ============================================================

    private static class Args {
        boolean prepare = false;
        boolean genDataset = false;
        boolean skipRerank = false;
        String datasetPath = "data/eval/dataset_v1.json";
        String outDir = "data/eval/reports";
        String note = "";
        String questionsDir = "data/questions";
        String manifestPath = "data/eval/manifest.json";
        int sampleCount = 50;
    }

    private static Args parseArgs(String[] args) {
        Args a = new Args();
        for (int i = 0; i < args.length; i++) {
            String key = args[i].replaceFirst("^--?", "");
            switch (key) {
                case "prepare" -> a.prepare = true;
                case "gen-dataset" -> a.genDataset = true;
                case "skip-rerank" -> a.skipRerank = true;
                case "dataset" -> a.datasetPath = next(args, ++i);
                case "out" -> a.outDir = next(args, ++i);
                case "note" -> a.note = next(args, ++i);
                case "questions" -> a.questionsDir = next(args, ++i);
                case "manifest" -> a.manifestPath = next(args, ++i);
                case "sample-count" -> a.sampleCount = Integer.parseInt(next(args, ++i));
                default -> log.warn("[Eval] 忽略未知参数: {}", args[i]);
            }
        }
        return a;
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("参数缺少值");
        }
        return args[i];
    }
}
