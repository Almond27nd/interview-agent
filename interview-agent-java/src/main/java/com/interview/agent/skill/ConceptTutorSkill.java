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

/**
 * 概念精讲技能（与 Go 版本一致）
 * - 将知识点拆解为多个关键点
 * - 逐个讲解，结合 RAG 知识库
 * - 三阶段：start → teaching → conclusion
 */
@Slf4j
public class ConceptTutorSkill implements Skill {

    private static final List<String> TUTOR_TRIGGERS = List.of(
            "讲讲", "讲一下", "什么是", "原理", "概念", "解释", "详解",
            "深入", "底层", "怎么实现", "实现原理", "工作原理"
    );
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel chatModel;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;

    public ConceptTutorSkill(ChatModel chatModel, MilvusStore milvusStore, BM25Manager bm25Manager) {
        this.chatModel = chatModel;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
    }

    @Override
    public String name() { return "concept_tutor"; }

    @Override
    public String description() { return "概念精讲：将知识点拆解为关键要点逐个讲解"; }

    @Override
    public boolean match(String input) {
        return SkillUtils.containsAny(input, TUTOR_TRIGGERS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillResponse handle(String input, SkillState state) {
        if (SkillUtils.isQuitCommand(input)) {
            return SkillResponse.builder().content("已退出概念精讲模式。").done(true).state(state).build();
        }
        if (state == null) state = SkillState.create(name());
        String phase = (String) state.getData().getOrDefault("phase", "start");

        return switch (phase) {
            case "start" -> handleStart(input, state);
            case "teaching" -> handleTeaching(input, state);
            default -> handleStart(input, state);
        };
    }

    private SkillResponse handleStart(String input, SkillState state) {
        String topic = parseTutorTopic(input);
        state.getData().put("topic", topic);

        // 获取 RAG 上下文
        String ragContext = fetchRAGContext(state.getUserId(), topic);

        // 拆解关键要点
        List<String> keyPoints = decomposeKeyPoints(topic, ragContext);
        if (keyPoints.isEmpty()) {
            keyPoints = List.of(topic);
        }

        state.getData().put("key_points", keyPoints);
        state.getData().put("current_point_index", 0);
        state.getData().put("phase", "teaching");
        state.getData().put("rag_context", ragContext);

        // 讲解第一个要点
        String explanation = explainPoint(topic, keyPoints.get(0), ragContext);

        StringBuilder content = new StringBuilder();
        content.append(String.format("📚 **%s** 精讲开始！\n\n", topic));
        content.append(String.format("我会从以下 %d 个关键要点来讲解：\n", keyPoints.size()));
        for (int i = 0; i < keyPoints.size(); i++) {
            content.append(String.format("%d. %s\n", i + 1, keyPoints.get(i)));
        }
        content.append(String.format("\n---\n\n**要点 1：%s**\n\n%s", keyPoints.get(0), explanation));

        String nextPrompt = keyPoints.size() > 1
                ? "理解了吗？输入'继续'看下一个要点，或提问。"
                : "以上就是全部内容。有什么疑问吗？";

        return SkillResponse.builder()
                .content(content.toString())
                .done(false)
                .nextPrompt(nextPrompt)
                .state(state)
                .build();
    }

    @SuppressWarnings("unchecked")
    private SkillResponse handleTeaching(String input, SkillState state) {
        List<String> keyPoints = (List<String>) state.getData().get("key_points");
        int currentIdx = ((Number) state.getData().get("current_point_index")).intValue();
        String topic = (String) state.getData().get("topic");
        String ragContext = (String) state.getData().getOrDefault("rag_context", "");

        boolean isUnderstand = isUnderstandResponse(input);
        if (isUnderstand) {
            currentIdx++;
            state.getData().put("current_point_index", currentIdx);
            state.nextRound();

            if (currentIdx >= keyPoints.size()) {
                // 所有要点讲完，总结
                String summary = String.format("""
                        ✅ **%s** 的所有关键要点已讲完！

                        回顾一下：
                        %s

                        如果还有疑问，可以继续提问。""",
                        topic,
                        buildPointsList(keyPoints));
                return SkillResponse.builder().content(summary).done(true).state(state).build();
            }

            String explanation = explainPoint(topic, keyPoints.get(currentIdx), ragContext);
            String content = String.format("**要点 %d：%s**\n\n%s",
                    currentIdx + 1, keyPoints.get(currentIdx), explanation);

            String nextPrompt = (currentIdx + 1 < keyPoints.size())
                    ? "理解了吗？输入'继续'看下一个要点，或提问。"
                    : "以上就是最后一个要点了，输入'继续'完成学习。";

            return SkillResponse.builder()
                    .content(content)
                    .done(false)
                    .nextPrompt(nextPrompt)
                    .state(state)
                    .build();
        }

        // 用户提问，回答后继续
        String currentPoint = currentIdx < keyPoints.size() ? keyPoints.get(currentIdx) : topic;
        String answer = answerQuestion(topic, currentPoint, input, ragContext);
        return SkillResponse.builder()
                .content(answer)
                .done(false)
                .nextPrompt("还有问题吗？输入'继续'看下一个要点。")
                .state(state)
                .build();
    }

    private List<String> decomposeKeyPoints(String topic, String ragContext) {
        String prompt = String.format("""
                请将「%s」这个知识点拆解为 3-5 个关键要点。

                %s

                请输出 JSON 数组格式，每个元素是一个要点的标题（简短）：
                ["要点1", "要点2", "要点3"]
                只输出 JSON 数组。""",
                topic,
                ragContext.isEmpty() ? "" : "参考资料：\n" + ragContext);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String json = response.getResult().getOutput().getText().trim();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ConceptTutor] 拆解要点失败: {}", e.getMessage());
            return List.of(topic);
        }
    }

    private String explainPoint(String topic, String point, String ragContext) {
        String prompt = String.format("""
                请详细讲解「%s」中的「%s」这个要点。

                要求：
                1. 用通俗易懂的语言讲解
                2. 如果合适，用类比帮助理解
                3. 给出关键结论
                %s""",
                topic, point,
                ragContext.isEmpty() ? "" : "\n参考资料：\n" + ragContext);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            return "讲解生成失败，请重试。";
        }
    }

    private String answerQuestion(String topic, String currentPoint, String question, String ragContext) {
        String prompt = String.format("""
                用户在学习「%s」的「%s」时提了一个问题：%s

                请针对性地回答。%s""",
                topic, currentPoint, question,
                ragContext.isEmpty() ? "" : "\n参考资料：\n" + ragContext);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            return "回答生成失败，请重试。";
        }
    }

    private String fetchRAGContext(String userId, String topic) {
        if (userId == null || userId.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        try {
            if (milvusStore != null) {
                List<RagDocument> docs = milvusStore.retrieveByUser(userId, topic, 3);
                for (RagDocument doc : docs) {
                    context.append(doc.getContent()).append("\n\n");
                }
            }
            if (bm25Manager != null) {
                List<RagDocument> bm25Docs = bm25Manager.retrieve(userId, topic);
                int limit = Math.min(3, bm25Docs.size());
                for (int i = 0; i < limit; i++) {
                    context.append(bm25Docs.get(i).getContent()).append("\n\n");
                }
            }
        } catch (Exception e) {
            log.warn("[ConceptTutor] 获取 RAG 上下文失败: {}", e.getMessage());
        }
        return context.toString().trim();
    }

    private String parseTutorTopic(String input) {
        String lower = input;
        for (String trigger : TUTOR_TRIGGERS) {
            lower = lower.replace(trigger, "");
        }
        return lower.trim().isEmpty() ? input : lower.trim();
    }

    private boolean isUnderstandResponse(String input) {
        String lower = input.trim().toLowerCase();
        return lower.equals("继续") || lower.equals("下一个") || lower.equals("next")
                || lower.equals("懂了") || lower.equals("明白了") || lower.equals("理解了")
                || lower.equals("ok") || lower.equals("好的");
    }

    private String buildPointsList(List<String> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            sb.append(String.format("%d. %s\n", i + 1, points.get(i)));
        }
        return sb.toString();
    }
}
