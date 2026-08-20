/**
 */
package com.interview.agent.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.agent.AgentUtils;
import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快速测验技能（与 Go 版本一致）
 * - 解析主题与题数（默认 5 道，支持「来5道Redis题」，范围 1~10）
 * - RAG 检索题目，不足部分用 LLM 补齐
 * - 逐题作答评分（含命中/遗漏要点）
 * - 最后给出总结与薄弱点
 */
@Slf4j
public class QuickQuizSkill implements Skill {

    // 触发快速测验的动作词（对齐 Go quizActions）
    private static final List<String> QUIZ_ACTIONS = List.of(
            "练", "测验", "考考", "出题", "来几道", "做几道", "练几道",
            "快速测验", "出几道", "来道", "做道", "练道"
    );
    // 测验类上下文关键词（对齐 Go quizContexts）
    private static final List<String> QUIZ_CONTEXTS = List.of("题", "面试题", "问题");
    // 明确的技术主题（对齐 Go hasNumberOrTopic）
    private static final List<String> QUIZ_TOPICS = List.of(
            "go", "redis", "mysql", "kafka", "docker", "k8s",
            "grpc", "http", "并发", "网络", "数据库", "算法", "系统设计", "微服务"
    );
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d+)\\s*道");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel chatModel;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;

    public QuickQuizSkill(ChatModel chatModel, MilvusStore milvusStore, BM25Manager bm25Manager) {
        this.chatModel = chatModel;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
    }

    @Override
    public String name() { return "quick_quiz"; }

    @Override
    public String description() { return "快速测验——输入主题即可练习面试题，即时评分反馈"; }

    @Override
    public boolean match(String input) {
        String lower = input.toLowerCase();
        if (SkillUtils.containsAny(lower, QUIZ_ACTIONS)) {
            return true;
        }
        // “5道Redis题”这类也能匹配
        return SkillUtils.containsAny(lower, QUIZ_CONTEXTS) && hasNumberOrTopic(lower);
    }

    @Override
    public SkillResponse handle(String input, SkillState state) {
        if (SkillUtils.isQuitCommand(input)) {
            return SkillResponse.builder()
                    .content("已退出测验模式。")
                    .done(true)
                    .state(state)
                    .build();
        }

        if (state == null || state.getData().get("phase") == null) {
            return handleStart(input, state);
        }

        String phase = (String) state.getData().getOrDefault("phase", "start");
        return switch (phase) {
            case "answering" -> handleAnswering(input, state);
            default -> handleStart(input, state);
        };
    }

    private SkillResponse handleStart(String input, SkillState state) {
        // 解析主题与题数
        QuizInput qi = parseQuizInput(input);
        String topic = qi.topic().isEmpty() ? "技术面试" : qi.topic();
        int count = qi.count();
        if (count <= 0 || count > 10) {
            count = 5;
        }

        // 复用已有 state（保留 userID），或创建新的
        if (state == null) {
            state = SkillState.create(name());
        }

        // 从题库检索题目
        List<Map<String, Object>> questions = retrieveQuestions(state.getUserId(), topic, count);
        int ragCount = questions.size();

        // RAG 不足的部分用 LLM 生成补充
        if (questions.size() < count) {
            List<Map<String, Object>> extra = generateQuestions(topic, count - questions.size());
            questions.addAll(extra);
        }

        if (questions.isEmpty()) {
            return SkillResponse.builder()
                    .content("抱歉，没有找到相关题目，也无法生成。请换个话题试试。")
                    .done(true)
                    .state(state)
                    .build();
        }

        state.getData().put("phase", "answering");
        state.getData().put("questions", questions);
        state.getData().put("current_index", 0);
        state.getData().put("scores", new ArrayList<Object>());
        state.getData().put("topic", topic);

        // 构建启动提示语（区分题库检索和 LLM 生成）
        String sourceHint;
        if (ragCount > 0 && ragCount >= questions.size()) {
            sourceHint = String.format("从题库检索到 %d 道题", questions.size());
        } else if (ragCount > 0) {
            sourceHint = String.format("从题库检索到 %d 道，LLM 补充生成 %d 道，共 %d 道题",
                    ragCount, questions.size() - ragCount, questions.size());
        } else {
            sourceHint = String.format("题库暂无相关题目，已由 AI 生成 %d 道题", questions.size());
        }

        Map<String, Object> firstQ = questions.get(0);
        String diffLabel = difficultyLabel((String) firstQ.getOrDefault("difficulty", "medium"));
        String content = String.format(
                "正在准备「%s」相关面试题...\n%s，快速测验开始！\n\n---\n\n📝 **第 1/%d 题**（%s）\n\n%s",
                topic, sourceHint, questions.size(), diffLabel, firstQ.get("content"));

        return SkillResponse.builder()
                .content(content)
                .done(false)
                .nextPrompt("请输入你的回答（输入 /quit 退出测验）")
                .state(state)
                .build();
    }

    @SuppressWarnings("unchecked")
    private SkillResponse handleAnswering(String input, SkillState state) {
        List<Map<String, Object>> questions = (List<Map<String, Object>>) state.getData().get("questions");
        int currentIndex = ((Number) state.getData().get("current_index")).intValue();
        List<Object> scores = (List<Object>) state.getData().get("scores");

        if (currentIndex >= questions.size()) {
            return buildSummary(state);
        }

        Map<String, Object> currentQ = questions.get(currentIndex);

        // 评分
        Map<String, Object> scoreResult = scoreAnswer(
                (String) currentQ.get("content"),
                input,
                (String) currentQ.getOrDefault("reference", "")
        );

        double score = ((Number) scoreResult.getOrDefault("score", 0)).doubleValue();
        String feedback = (String) scoreResult.getOrDefault("feedback", "");
        List<String> hit = toStringList(scoreResult.get("key_points_hit"));
        List<String> missed = toStringList(scoreResult.get("key_points_missed"));

        scores.add(score);
        currentIndex++;
        state.getData().put("current_index", currentIndex);
        state.getData().put("scores", scores);
        state.nextRound();

        // 构建评分反馈（对齐 Go：得分 + 命中 + 遗漏 + 反馈）
        StringBuilder sb = new StringBuilder();
        if (score >= 70) {
            sb.append(String.format("✅ **得分：%.0f/100**\n\n", score));
        } else {
            sb.append(String.format("⚠️ **得分：%.0f/100**\n\n", score));
        }
        if (!hit.isEmpty()) {
            sb.append("**命中：**").append(String.join("、", hit)).append("\n\n");
        }
        if (!missed.isEmpty()) {
            sb.append("**遗漏：**").append(String.join("、", missed)).append("\n\n");
        }
        if (feedback != null && !feedback.isEmpty()) {
            sb.append(feedback).append("\n");
        }

        if (currentIndex >= questions.size()) {
            // 测验完成，给出总结
            sb.append("\n---\n\n");
            sb.append(buildSummaryContent(scores, questions, (String) state.getData().get("topic")));
            state.getData().put("phase", "done");
            return SkillResponse.builder()
                    .content(sb.toString())
                    .done(true)
                    .state(state)
                    .build();
        }

        Map<String, Object> nextQ = questions.get(currentIndex);
        String diffLabel = difficultyLabel((String) nextQ.getOrDefault("difficulty", "medium"));
        sb.append(String.format("\n---\n\n📝 **第 %d/%d 题**（%s）\n\n%s",
                currentIndex + 1, questions.size(), diffLabel, nextQ.get("content")));

        return SkillResponse.builder()
                .content(sb.toString())
                .done(false)
                .nextPrompt("请输入你的回答（输入 /quit 退出测验）")
                .state(state)
                .build();
    }

    private List<Map<String, Object>> retrieveQuestions(String userId, String topic, int count) {
        List<Map<String, Object>> questions = new ArrayList<>();
        try {
            String uid = (userId == null || userId.isEmpty()) ? "default_user" : userId;
            Set<String> seen = new HashSet<>();

            if (milvusStore != null) {
                List<RagDocument> docs = milvusStore.retrieveByUser(uid, topic, count * 2);
                for (RagDocument doc : docs) {
                    if (questions.size() >= count) break;
                    if (seen.contains(doc.getId())) continue;
                    seen.add(doc.getId());
                    Map<String, Object> q = toQuizQuestion(doc);
                    if (q != null) questions.add(q);
                }
            }

            if (questions.size() < count && bm25Manager != null) {
                List<RagDocument> bm25Docs = bm25Manager.retrieve(uid, topic);
                for (RagDocument doc : bm25Docs) {
                    if (questions.size() >= count) break;
                    if (seen.contains(doc.getId())) continue;
                    seen.add(doc.getId());
                    Map<String, Object> q = toQuizQuestion(doc);
                    if (q != null) questions.add(q);
                }
            }
        } catch (Exception e) {
            log.warn("[QuickQuiz] 检索题目失败: {}", e.getMessage());
        }
        return questions;
    }

    /** 将 RAG 文档转为测验题目（拆分题目/参考答案，提取难度） */
    private Map<String, Object> toQuizQuestion(RagDocument doc) {
        String[] parts = splitContentAndReference(doc.getContent());
        if (parts[0] == null || parts[0].isEmpty()) {
            return null;
        }
        String diff = "medium";
        if (doc.getMetadata() != null) {
            Object v = doc.getMetadata().get("difficulty");
            if (v instanceof String s && !s.isEmpty()) {
                diff = s;
            }
        }
        Map<String, Object> q = new HashMap<>();
        q.put("content", parts[0]);
        q.put("reference", parts[1]);
        q.put("difficulty", diff);
        return q;
    }

    private List<Map<String, Object>> generateQuestions(String topic, int count) {
        String prompt = String.format("""
                请生成 %d 道关于「%s」的技术面试题。
                要求：
                1. 难度从易到难递进
                2. 每道题包含题目内容和参考答案要点
                3. 只出概念理解、原理分析、场景设计类题目，不要出需要写代码或看代码输出的题目
                4. 输出纯 JSON 数组格式：
                [
                  {"content": "题目内容", "reference": "参考答案要点", "difficulty": "easy/medium/hard"}
                ]

                只输出 JSON，不要其他文字。""", count, topic);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String json = AgentUtils.extractJSON(response.getResult().getOutput().getText());
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            // 兜底：确保每题带 difficulty
            for (Map<String, Object> q : list) {
                q.putIfAbsent("difficulty", "medium");
                q.putIfAbsent("reference", "");
            }
            return new ArrayList<>(list);
        } catch (Exception e) {
            log.warn("[QuickQuiz] LLM 生成题目失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> scoreAnswer(String question, String answer, String reference) {
        String prompt = String.format("""
                请对候选人的回答进行客观评分和反馈。

                题目：%s
                候选人回答：%s
                参考答案要点：%s

                【核心原则】严格基于候选人实际回答的内容进行评分：
                - 只认定候选人明确表述出来的知识点
                - 候选人没有提到的知识点，一律算作遗漏
                - 候选人说"不会"、"不知道"等，得分应为 0-10 分

                请输出纯 JSON 格式：
                {
                  "score": <0-100>,
                  "feedback": "简要反馈",
                  "key_points_hit": ["命中的知识点"],
                  "key_points_missed": ["遗漏的知识点"]
                }""", question, answer, reference.isEmpty() ? "无" : reference);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String json = AgentUtils.extractJSON(response.getResult().getOutput().getText());
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[QuickQuiz] 评分失败: {}", e.getMessage());
            return Map.of("score", 0, "feedback", "评分失败，已跳过");
        }
    }

    private SkillResponse buildSummary(SkillState state) {
        @SuppressWarnings("unchecked")
        List<Object> scores = (List<Object>) state.getData().get("scores");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) state.getData().get("questions");
        String topic = (String) state.getData().get("topic");
        state.getData().put("phase", "done");
        return SkillResponse.builder()
                .content(buildSummaryContent(scores, questions, topic))
                .done(true)
                .state(state)
                .build();
    }

    private String buildSummaryContent(List<Object> scores, List<Map<String, Object>> questions, String topic) {
        int total = scores == null ? 0 : scores.size();
        if (total == 0) {
            return "📊 快速测验结束，没有作答记录。";
        }

        double sum = 0;
        List<String> scoreStrs = new ArrayList<>(total);
        for (Object s : scores) {
            double v = ((Number) s).doubleValue();
            sum += v;
            scoreStrs.add(String.format("%.0f", v));
        }
        double avg = sum / total;

        StringBuilder sb = new StringBuilder();
        sb.append("📊 **快速测验完成！**\n");
        sb.append(String.format("主题：%s\n", topic));
        sb.append(String.format("总得分：**%.0f/100**（%d 题平均）\n", avg, total));
        sb.append("各题：").append(String.join(" → ", scoreStrs)).append("\n");

        // 找薄弱题目（< 60 分），用题目内容提示
        List<String> weakTopics = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            double v = ((Number) scores.get(i)).doubleValue();
            if (v < 60 && questions != null && i < questions.size()) {
                String content = String.valueOf(questions.get(i).get("content"));
                if (content.length() > 30) {
                    content = content.substring(0, 30) + "...";
                }
                weakTopics.add(content);
            }
        }
        if (!weakTopics.isEmpty()) {
            sb.append("💡 薄弱点：").append(String.join("；", weakTopics)).append("\n");
            sb.append("建议通过「知识讲解」深入学习相关知识点（输入「讲讲 XXX」即可）。");
        }

        return sb.toString();
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 从用户输入中提取主题和题目数量（对齐 Go parseQuizInput） */
    private QuizInput parseQuizInput(String input) {
        int count = 5; // 默认 5 题

        // 提取「N 道」中的数字
        Matcher m = COUNT_PATTERN.matcher(input);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n > 0) count = n;
            } catch (NumberFormatException ignored) {}
        }

        // 移除触发词和数量词，剩下的就是主题
        String topic = input;
        List<String> removeWords = new ArrayList<>(QUIZ_ACTIONS);
        removeWords.addAll(List.of("道", "面试题", "面试", "相关", "关于", "的", "一下", "帮我", "给我"));
        for (String w : removeWords) {
            topic = topic.replace(w, "");
        }
        // 移除数字
        topic = topic.replaceAll("\\d+", "").trim();

        return new QuizInput(topic, count);
    }

    /** 判断输入中是否包含数字或明确的技术主题（对齐 Go hasNumberOrTopic） */
    private boolean hasNumberOrTopic(String input) {
        if (Pattern.compile("\\d").matcher(input).find()) {
            return true;
        }
        return SkillUtils.containsAny(input, QUIZ_TOPICS);
    }

    private String[] splitContentAndReference(String content) {
        String marker = "\n参考答案：";
        int idx = content.indexOf(marker);
        if (idx >= 0) {
            return new String[]{content.substring(0, idx).trim(), content.substring(idx + marker.length()).trim()};
        }
        // 兜底：无换行的「参考答案：」
        int idx2 = content.indexOf("参考答案：");
        if (idx2 >= 0) {
            return new String[]{content.substring(0, idx2).trim(), content.substring(idx2 + "参考答案：".length()).trim()};
        }
        return new String[]{content, ""};
    }

    private String difficultyLabel(String d) {
        return switch (d == null ? "" : d) {
            case "easy" -> "基础";
            case "hard" -> "困难";
            case "medium" -> "中等";
            default -> "中等";
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) result.add(String.valueOf(o));
            }
            return result;
        }
        return List.of();
    }

    /** 主题 + 题数 */
    private record QuizInput(String topic, int count) {}
}
