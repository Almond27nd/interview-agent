/**
 */
package com.interview.agent.mcp;

import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.RagDocument;
import com.interview.agent.rag.Reranker;
import com.interview.agent.rag.MilvusStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 题库检索工具（B1/B3 共用）：把 Milvus 向量检索 + BM25 检索 + Reranker 重排三步合并成一个
 * 可复用的检索能力，分别暴露成两种场景的 {@link ToolCallback}：
 * <ul>
 *   <li>{@link #asQuestionAssemblyTool(String)}：给 {@code QuestionPlanner} 出基础题时用，
 *       模型自主决定检索关键词、是否重试、是否直接用原题（B1：Agentic RAG）；</li>
 *   <li>{@link #asReviewRecommendTool(String)}：给 {@code ReviewPlanner} 生成复习计划时用，
 *       为薄弱点推荐真实存在的巩固练习题（B3：多工具智能体）。</li>
 * </ul>
 * 检索逻辑本身从 {@code Orchestrator.questionPlan()} 原来的手写 for 循环中抽取出来，
 * 抽取后该逻辑可以被"代码调用"（降级路径）和"模型通过工具调用"（Agentic 路径）两种方式复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionBankTool {

    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final Reranker reranker;

    /** 题库检索能力是否可用（至少一种检索源存在） */
    public boolean isAvailable() {
        return milvusStore != null || bm25Manager != null;
    }

    /**
     * 合并检索：Milvus + BM25 各取一份、按 id 去重，再统一交给 Reranker 重排，取前 topN 条。
     * 这是原 {@code Orchestrator.questionPlan()} 手写 RAG pipeline 的核心逻辑，抽取后既可以被
     * 传统的降级路径直接调用，也可以包装成 Tool 供 ReactAgent 自主调用。
     */
    public List<RagDocument> search(String userId, String query, int topN) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<RagDocument> docs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (milvusStore != null) {
            try {
                for (RagDocument doc : milvusStore.retrieveByUser(userId, query, 10)) {
                    if (seen.add(doc.getId())) {
                        docs.add(doc);
                    }
                }
            } catch (Exception e) {
                log.warn("[QuestionBankTool] Milvus 检索失败: {}", e.getMessage());
            }
        }

        if (bm25Manager != null) {
            try {
                for (RagDocument doc : bm25Manager.retrieve(userId, query)) {
                    if (seen.add(doc.getId())) {
                        docs.add(doc);
                    }
                }
            } catch (Exception e) {
                log.warn("[QuestionBankTool] BM25 检索失败: {}", e.getMessage());
            }
        }

        if (docs.isEmpty()) {
            return List.of();
        }

        List<RagDocument> reranked = reranker.rerank(query, docs, topN);
        if (reranked == null || reranked.isEmpty()) {
            return docs.subList(0, Math.min(topN, docs.size()));
        }
        return reranked;
    }

    /** 从题目内容里拆出参考答案（题库存储时用 "\n参考答案：" 拼接在题目正文之后） */
    private String[] splitContentAndReference(String raw) {
        int idx = raw.indexOf("\n参考答案：");
        if (idx < 0) {
            return new String[]{raw, ""};
        }
        return new String[]{raw.substring(0, idx).trim(), raw.substring(idx + "\n参考答案：".length()).trim()};
    }

    /**
     * B1：供 QuestionPlanner 的出题 ReactAgent 使用。模型可以据此判断"要不要用原题、
     * 关键词是否需要换一个再检索一次"——这正是 Agentic RAG 相对原来"一次检索、不中就转 LLM 兜底"
     * 的核心区别所在。
     */
    public ToolCallback asQuestionAssemblyTool(String userId) {
        return FunctionToolCallback
                .builder("search_question_bank", (SearchRequest req) -> {
                    String q = (req == null || req.query() == null) ? "" : req.query().trim();
                    if (q.isEmpty()) {
                        return "未提供检索关键词。";
                    }
                    List<RagDocument> docs = search(userId, q, 3);
                    if (docs.isEmpty()) {
                        return "题库未检索到与该关键词匹配的题目。可以换一个更精确或更宽泛的关键词再检索一次；"
                                + "如果换过关键词后仍无结果，请直接结合考察方向自行出题（source 填 llm）。";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (RagDocument doc : docs) {
                        String[] parts = splitContentAndReference(doc.getContent());
                        sb.append(String.format("- 题目ID: %s | 相似度: %.3f%n  内容: %s%n  参考答案: %s%n",
                                doc.getId(), doc.getScore(), parts[0], parts[1]));
                    }
                    sb.append("\n如果以上某条与本次考察方向高度匹配，直接使用其原文作为最终题目"
                            + "（content 完全照搬不得改编，source 填该题目ID）；如果都不太匹配，"
                            + "可以换个关键词重新调用本工具再试一次，或放弃检索、自行出题（source 填 llm）。");
                    return sb.toString();
                })
                .description("根据出题方向的关键词，从候选人专属题库中检索最相似的候选题目"
                        + "（返回相似度、题目内容、参考答案）。用于判断是否存在可直接使用的题库原题；"
                        + "第一次检索结果不满意时，可以换一个关键词再调用一次。")
                .inputType(SearchRequest.class)
                .build();
    }

    /**
     * B3：供 ReviewPlanner 生成复习计划时使用。为某个薄弱点推荐"巩固练习题"时，
     * 让模型自己检索题库找真实存在的题目，而不是空泛地说"多练习 XX"。
     */
    public ToolCallback asReviewRecommendTool(String userId) {
        return FunctionToolCallback
                .builder("search_practice_questions", (SearchRequest req) -> {
                    String q = (req == null || req.query() == null) ? "" : req.query().trim();
                    if (q.isEmpty()) {
                        return "未提供检索关键词。";
                    }
                    List<RagDocument> docs = search(userId, q, 3);
                    if (docs.isEmpty()) {
                        return "题库中未检索到该主题的练习题，可换个关键词再试，或不推荐具体题目、只给学习建议。";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (RagDocument doc : docs) {
                        String[] parts = splitContentAndReference(doc.getContent());
                        sb.append("- [题目ID: ").append(doc.getId()).append("] ").append(parts[0]).append("\n");
                    }
                    return sb.toString();
                })
                .description("根据薄弱知识点关键词，从候选人专属题库中检索可作为\"巩固练习\"的真实题目"
                        + "（返回题目ID与内容）。生成复习计划的学习项时，如需要给出具体练习题建议可调用本工具，"
                        + "避免空泛地说\"多练习 XX\"却给不出真实题目。")
                .inputType(SearchRequest.class)
                .build();
    }

    /** ReactAgent 调用本工具时的入参 */
    public record SearchRequest(String query) {}
}
