/**
 */
package com.interview.agent.skill;

import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

/**
 * 技术对比技能（与 Go 版本一致）
 * - 两阶段：start → advising
 * - 表格对比 + 选型建议 + 面试话术
 */
@Slf4j
public class TechCompareSkill implements Skill {

    private static final List<String> COMPARE_TRIGGERS = List.of(
            "对比", "区别", "vs", "差异", "哪个好", "选型",
            "比较", "异同", "优劣", "versus"
    );

    private final ChatModel chatModel;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;

    public TechCompareSkill(ChatModel chatModel, MilvusStore milvusStore, BM25Manager bm25Manager) {
        this.chatModel = chatModel;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
    }

    @Override
    public String name() { return "tech_compare"; }

    @Override
    public String description() { return "技术对比：表格对比 + 选型建议 + 面试话术"; }

    @Override
    public boolean match(String input) {
        return SkillUtils.containsAny(input, COMPARE_TRIGGERS);
    }

    @Override
    public SkillResponse handle(String input, SkillState state) {
        if (SkillUtils.isQuitCommand(input)) {
            return SkillResponse.builder().content("已退出技术对比模式。").done(true).state(state).build();
        }
        if (state == null) state = SkillState.create(name());
        String phase = (String) state.getData().getOrDefault("phase", "start");

        return switch (phase) {
            case "start" -> handleStart(input, state);
            case "advising" -> handleAdvising(input, state);
            default -> handleStart(input, state);
        };
    }

    private SkillResponse handleStart(String input, SkillState state) {
        state.getData().put("topic", input);
        state.getData().put("phase", "advising");

        String ragContext = fetchRAGContext(state.getUserId(), input);

        String prompt = String.format("""
                请对以下技术进行全面对比分析：

                用户问题：%s

                请输出三个部分：
                1. **对比表格**（Markdown 表格格式，从多个维度对比）
                2. **选型建议**（不同场景下推荐的选择）
                3. **面试话术**（面试中如何回答这类对比题，给出参考回答）
                %s""",
                input,
                ragContext.isEmpty() ? "" : "\n参考资料：\n" + ragContext);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String content = "⚖️ **技术对比分析**\n\n" + response.getResult().getOutput().getText();

            return SkillResponse.builder()
                    .content(content)
                    .done(false)
                    .nextPrompt("如果你有具体的使用场景，可以告诉我，我给你更有针对性的建议。输入'退出'结束。")
                    .state(state)
                    .build();
        } catch (Exception e) {
            return SkillResponse.builder()
                    .content("对比分析生成失败，请重试。")
                    .done(true)
                    .state(state)
                    .build();
        }
    }

    private SkillResponse handleAdvising(String input, SkillState state) {
        String topic = (String) state.getData().get("topic");

        String prompt = String.format("""
                用户在对比「%s」后，描述了自己的具体场景：
                %s

                请给出针对性的选型推荐，说明理由。""", topic, input);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return SkillResponse.builder()
                    .content("🎯 **针对性建议**\n\n" + response.getResult().getOutput().getText())
                    .done(true)
                    .state(state)
                    .build();
        } catch (Exception e) {
            return SkillResponse.builder()
                    .content("建议生成失败，请重试。")
                    .done(true)
                    .state(state)
                    .build();
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
            log.warn("[TechCompare] 获取 RAG 上下文失败: {}", e.getMessage());
        }
        return context.toString().trim();
    }
}
