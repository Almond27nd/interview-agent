/**
 */
package com.interview.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM 重排策略：用大模型对候选文档做语义重排。
 * - 截取前 200 字符避免 prompt 过长
 * - 全量重排（不过滤丢弃）：朴素 rerank 用"过滤掉不相关"的 prompt 会让 LLM 误删相关题、
 *   反而拉低 Recall（实测整体 0.7567→0.6533）。改为只重排序、保留全部候选，把召回在 11-20 位
 *   的相关题提进前 10，让 rerank 从负优化变正优化。
 * - 失败时返回原始顺序。
 */
@Slf4j
@Component
public class LlmRerankStrategy implements RerankStrategy {

    private static final String RERANK_PROMPT = """
            你是一个文档相关性排序专家。请根据用户查询，对以下【全部】候选文档按相关性从高到低重新排序。

            重要规则：必须包含上面所有候选文档的 ID，不要丢弃、不要过滤掉任何文档，只调整它们的先后顺序——把与查询最相关的排在最前面，不太相关的排在后面。

            用户查询：%s

            候选文档：
            %s

            请输出重排序后的【完整】文档 ID 列表（JSON 数组格式，从最相关到最不相关，必须包含上面出现的每一个文档 ID），只输出 JSON 数组，不要输出其他内容。
            示例输出：["doc_3", "doc_1", "doc_2"]""";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel chatModel;

    public LlmRerankStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public List<RagDocument> rerank(String query, List<RagDocument> docs, int topN) {
        if (docs == null || docs.size() <= 1) {
            return docs;
        }

        // 构造候选文档列表文本
        StringBuilder docsText = new StringBuilder();
        Map<String, RagDocument> docMap = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            RagDocument doc = docs.get(i);
            String id = (doc.getId() != null && !doc.getId().isEmpty()) ? doc.getId() : "doc_" + i;
            docMap.put(id, doc);
            String content = doc.getContent();
            if (content != null && content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            docsText.append(String.format("[%s] %s\n", id, content));
        }

        String prompt = String.format(RERANK_PROMPT, query, docsText);
        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String respText = response.getResult().getOutput().getText().trim();

            // 提取 [...] 部分
            int start = respText.indexOf('[');
            int end = respText.lastIndexOf(']');
            if (start >= 0 && end > start) {
                respText = respText.substring(start, end + 1);
            }

            List<String> rankedIDs = objectMapper.readValue(respText, new TypeReference<>() {});

            // 按 LLM 排序重组；不丢弃任何候选——LLM 漏掉/误删的按原召回顺序补在末尾，
            // 保证 rerank 不会减少召回集合，只改善排序（相关题前移），从而不会拉低 Recall。
            List<RagDocument> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String id : rankedIDs) {
                RagDocument doc = docMap.get(id);
                if (doc != null && seen.add(id)) {
                    result.add(doc);
                }
            }
            for (int i = 0; i < docs.size(); i++) {
                RagDocument doc = docs.get(i);
                String id = (doc.getId() != null && !doc.getId().isEmpty()) ? doc.getId() : "doc_" + i;
                if (seen.add(id)) {
                    result.add(doc);
                }
            }
            return result.size() > topN ? new ArrayList<>(result.subList(0, topN)) : result;
        } catch (Exception e) {
            log.warn("[Reranker:llm] 重排序失败，返回原始顺序: {}", e.getMessage());
            return docs.subList(0, Math.min(topN, docs.size()));
        }
    }
}
