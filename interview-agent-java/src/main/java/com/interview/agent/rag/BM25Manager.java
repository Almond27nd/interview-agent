/**
 */
package com.interview.agent.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BM25 管理器：按用户管理 BM25 索引实例（与 Go 版本一致）
 */
@Component
public class BM25Manager {

    private final Map<String, BM25Retriever> retrievers = new ConcurrentHashMap<>();
    private final int topK;

    public BM25Manager() {
        this.topK = 10;
    }

    public BM25Manager(int topK) {
        this.topK = topK > 0 ? topK : 10;
    }

    /**
     * 覆盖指定用户的题库索引
     */
    public void replaceDocuments(String userID, List<RagDocument> docs) {
        BM25Retriever r = new BM25Retriever(topK);
        r.indexDocuments(docs);
        retrievers.put(userID, r);
    }

    /**
     * 追加文档到指定用户的索引
     */
    public void appendDocuments(String userID, List<RagDocument> docs) {
        BM25Retriever r = retrievers.computeIfAbsent(userID, k -> new BM25Retriever(topK));
        r.appendDocuments(docs);
    }

    /**
     * 检索指定用户的题库
     */
    public List<RagDocument> retrieve(String userID, String query) {
        BM25Retriever r = retrievers.get(userID);
        if (r == null) {
            return List.of();
        }
        return r.retrieve(query);
    }

    /**
     * 删除指定用户的索引
     */
    public void deleteByUserID(String userID) {
        retrievers.remove(userID);
    }

    /**
     * 删除指定用户某个来源文件的题目（与 MilvusStore.deleteBySourceFile 保持一致的覆盖式更新语义）
     */
    public void deleteBySourceFile(String userID, String sourceFile) {
        BM25Retriever r = retrievers.get(userID);
        if (r != null) {
            r.removeBySourceFile(sourceFile);
        }
    }
}
