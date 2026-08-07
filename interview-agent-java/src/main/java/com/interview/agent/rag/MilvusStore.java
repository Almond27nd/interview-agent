/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.config.AppConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.response.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储（与 Go 版本一致）
 * - 集合名：interview_questions
 * - 向量维度：1024（text-embedding-v3）
 * - 支持按用户隔离检索、按来源文件删除
 */
@Slf4j
@Component
public class MilvusStore {

    public static final String COLLECTION_NAME = "interview_questions";
    public static final int VECTOR_DIMENSION = 1024;
    private static final int BATCH_SIZE = 10;

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MilvusStore(MilvusClientV2 milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void init() {
        try {
            // 检查集合是否存在
            boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(COLLECTION_NAME).build());
            if (!exists) {
                createCollection();
            }
            // 加载集合到内存
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(COLLECTION_NAME).build());
            log.info("[Milvus] 集合 {} 就绪", COLLECTION_NAME);
        } catch (Exception e) {
            log.error("[Milvus] 初始化失败: {}", e.getMessage());
        }
    }

    private void createCollection() {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar).maxLength(256).isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(8192).build());
        schema.addField(AddFieldReq.builder().fieldName("vector").dataType(DataType.FloatVector).dimension(VECTOR_DIMENSION).build());
        schema.addField(AddFieldReq.builder().fieldName("metadata").dataType(DataType.JSON).build());
        schema.addField(AddFieldReq.builder().fieldName("user_id").dataType(DataType.VarChar).maxLength(128).build());
        schema.addField(AddFieldReq.builder().fieldName("source_file").dataType(DataType.VarChar).maxLength(256).build());

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build());
        log.info("[Milvus] 创建集合: {}", COLLECTION_NAME);
    }

    /**
     * 将文档写入 Milvus（自动分批，每批最多 10 条）
     */
    public List<String> store(List<RagDocument> docs) {
        List<String> allIDs = new ArrayList<>();

        for (int i = 0; i < docs.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, docs.size());
            List<RagDocument> batch = docs.subList(i, end);

            // 获取嵌入向量
            List<float[]> vectors = new ArrayList<>();
            for (RagDocument doc : batch) {
                vectors.add(embeddingModel.embed(doc.getContent()));
            }

            List<com.google.gson.JsonObject> data = new ArrayList<>();
            for (int j = 0; j < batch.size(); j++) {
                RagDocument doc = batch.get(j);
                com.google.gson.JsonObject row = new com.google.gson.JsonObject();
                row.addProperty("id", doc.getId());
                row.addProperty("content", doc.getContent());
                row.add("vector", toJsonArray(vectors.get(j)));
                row.add("metadata", com.google.gson.JsonParser.parseString(
                        toJsonString(doc.getMetadata())));
                row.addProperty("user_id", doc.getUserId());
                row.addProperty("source_file", doc.getSourceFile());
                data.add(row);
            }

            milvusClient.insert(InsertReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .data(data)
                    .build());

            batch.forEach(d -> allIDs.add(d.getId()));
            log.info("[Milvus] 写入批次 {}-{}，{} 篇文档", i, end, batch.size());
        }

        log.info("[Milvus] 共写入 {} 篇文档", allIDs.size());
        return allIDs;
    }

    /**
     * 检索指定用户的题目
     */
    public List<RagDocument> retrieveByUser(String userID, String query, int topK) {
        if (topK <= 0) topK = 10;

        // 获取查询向量
        float[] queryVector = embeddingModel.embed(query);

        String filter = String.format("user_id == \"%s\"", userID);

        SearchResp resp = milvusClient.search(SearchReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(Collections.singletonList(new io.milvus.v2.service.vector.request.data.FloatVec(toFloatList(queryVector))))
                .annsField("vector")
                .filter(filter)
                .topK(topK)
                .outputFields(Arrays.asList("id", "content", "metadata"))
                .build());

        return parseSearchResults(resp);
    }

    /**
     * 通用检索（不限用户）
     */
    public List<RagDocument> retrieve(String query, int topK) {
        if (topK <= 0) topK = 10;

        float[] queryVector = embeddingModel.embed(query);

        SearchResp resp = milvusClient.search(SearchReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(Collections.singletonList(new io.milvus.v2.service.vector.request.data.FloatVec(toFloatList(queryVector))))
                .annsField("vector")
                .topK(topK)
                .outputFields(Arrays.asList("id", "content", "metadata"))
                .build());

        return parseSearchResults(resp);
    }

    /**
     * 删除指定用户的所有题目
     */
    public void deleteByUserID(String userID) {
        String filter = String.format("user_id == \"%s\"", userID);
        milvusClient.delete(DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build());
        log.info("[Milvus] 已删除用户 {} 的所有题目", userID);
    }

    /**
     * 删除指定用户某个来源文件的题目
     */
    public void deleteBySourceFile(String userID, String sourceFile) {
        String filter = String.format("user_id == \"%s\" && source_file == \"%s\"", userID, sourceFile);
        milvusClient.delete(DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build());
        log.info("[Milvus] 已删除用户 {} 文件 {} 的题目", userID, sourceFile);
    }

    /**
     * 将解析后的结构化题目写入 Milvus
     */
    public void loadParsedQuestions(String userID, String sourceFile, List<ParsedQuestionInput> questions) {
        List<RagDocument> docs = questions.stream().map(q -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", q.getType());
            metadata.put("difficulty", q.getDifficulty());
            metadata.put("skills", q.getSkills());
            metadata.put("reference", q.getReference());
            metadata.put("user_id", userID);
            metadata.put("source_file", sourceFile);

            return RagDocument.builder()
                    .id(q.getId())
                    .content(q.getContent() + "\n参考答案：" + q.getReference())
                    .metadata(metadata)
                    .userId(userID)
                    .sourceFile(sourceFile)
                    .build();
        }).collect(Collectors.toList());

        store(docs);
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ParsedQuestionInput {
        private String id;
        private String content;
        private String reference;
        private String type;
        private String difficulty;
        private List<String> skills;
    }

    private List<RagDocument> parseSearchResults(SearchResp resp) {
        List<RagDocument> results = new ArrayList<>();
        if (resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return results;
        }
        for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
            for (SearchResp.SearchResult sr : resultList) {
                Map<String, Object> entity = sr.getEntity();
                RagDocument doc = RagDocument.builder()
                        .id(String.valueOf(entity.getOrDefault("id", "")))
                        .content(String.valueOf(entity.getOrDefault("content", "")))
                        .score(sr.getScore())
                        .build();

                Object metaObj = entity.get("metadata");
                if (metaObj != null) {
                    try {
                        if (metaObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> meta = (Map<String, Object>) metaObj;
                            doc.setMetadata(meta);
                        } else {
                            doc.setMetadata(objectMapper.readValue(metaObj.toString(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                        }
                    } catch (Exception e) {
                        log.warn("[Milvus] 解析 metadata 失败: {}", e.getMessage());
                    }
                }
                results.add(doc);
            }
        }
        return results;
    }

    private com.google.gson.JsonArray toJsonArray(float[] vector) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (float v : vector) arr.add(v);
        return arr;
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) list.add(v);
        return list;
    }

    private String toJsonString(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map != null ? map : Collections.emptyMap());
        } catch (Exception e) {
            return "{}";
        }
    }
}
