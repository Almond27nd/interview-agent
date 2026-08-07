/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25Retriever 测试：验证中文分词改进（jieba 替代朴素标点切分）和
 * removeBySourceFile（按来源文件覆盖式更新）两处改进点。
 */
class BM25RetrieverTest {

    // ------------------------- 分词改进：jieba 分词 -------------------------

    @Test
    @DisplayName("中文长句应被切分成多个有意义的词，而不是整句当一个大 token")
    void tokenizeSplitsChineseSentenceIntoMultipleWords() {
        List<String> tokens = BM25Retriever.tokenize("什么是MySQL的MVCC机制");

        // 朴素实现下这句话没有任何标点/空格，整句会被当成一个 token；
        // jieba 分词后应该拆出多个独立词语
        assertTrue(tokens.size() > 1, "分词结果应包含多个 token，实际: " + tokens);
        // "mysql" 和 "mvcc" 作为专有名词/英文词应该被正确识别为独立 token
        assertTrue(tokens.contains("mysql"), "应包含 mysql，实际: " + tokens);
        assertTrue(tokens.contains("mvcc"), "应包含 mvcc，实际: " + tokens);
        // 常见停用词"的""是"应被过滤掉
        assertFalse(tokens.contains("的"), "停用词'的'不应出现在分词结果里");
        assertFalse(tokens.contains("是"), "停用词'是'不应出现在分词结果里");
    }

    @Test
    @DisplayName("纯标点/空白应被过滤，不产生空 token")
    void tokenizeFiltersPunctuationOnlyTokens() {
        List<String> tokens = BM25Retriever.tokenize("Go语言中sync.Map和加锁map相比，有什么优势？");

        assertFalse(tokens.isEmpty());
        assertTrue(tokens.stream().noneMatch(String::isBlank), "不应包含空白 token");
        assertTrue(tokens.stream().noneMatch(t -> t.matches("^[\\p{Punct}，。？！；：]+$")),
                "不应包含纯标点 token，实际: " + tokens);
        // 英文技术词依旧应被正确切出来
        assertTrue(tokens.contains("sync"), "实际: " + tokens);
        assertTrue(tokens.contains("map"), "实际: " + tokens);
    }

    @Test
    @DisplayName("空文本/空白文本分词结果应为空列表，不抛异常")
    void tokenizeHandlesBlankInput() {
        assertTrue(BM25Retriever.tokenize(null).isEmpty());
        assertTrue(BM25Retriever.tokenize("").isEmpty());
        assertTrue(BM25Retriever.tokenize("   ").isEmpty());
    }

    @Test
    @DisplayName("中文分词后，跨文档共享的中文关键词也能被倒排索引正确命中")
    void chineseKeywordIsRetrievableAfterJiebaTokenization() {
        BM25Retriever retriever = new BM25Retriever(10);
        retriever.indexDocuments(List.of(
                RagDocument.builder().id("q1")
                        .content("什么是MySQL的MVCC机制？\n参考答案：MVCC通过版本链和读视图实现无锁读。")
                        .build(),
                RagDocument.builder().id("q2")
                        .content("Go语言中sync.Map和普通map加锁相比有什么优势？\n参考答案：sync.Map内部通过读写分离结构避免了锁竞争。")
                        .build()
        ));

        // 朴素实现下"版本链"这种中文词汇无法被独立索引和检索到；jieba 分词后应能命中
        List<RagDocument> results = retriever.retrieve("版本链");
        assertEquals(1, results.size());
        assertEquals("q1", results.get(0).getId());
    }

    // ------------------------- BM25 更新改进：removeBySourceFile -------------------------

    @Test
    @DisplayName("removeBySourceFile 应只删除匹配文件的文档，不影响其他文件的文档")
    void removeBySourceFileOnlyRemovesMatchingDocs() {
        BM25Retriever retriever = new BM25Retriever(10);
        retriever.appendDocuments(List.of(
                RagDocument.builder().id("a1").content("题目A1").sourceFile("fileA.pdf").build(),
                RagDocument.builder().id("a2").content("题目A2").sourceFile("fileA.pdf").build(),
                RagDocument.builder().id("b1").content("题目B1").sourceFile("fileB.pdf").build()
        ));
        assertEquals(3, retriever.getDocuments().size());

        retriever.removeBySourceFile("fileA.pdf");

        List<RagDocument> remaining = retriever.getDocuments();
        assertEquals(1, remaining.size());
        assertEquals("b1", remaining.get(0).getId());
    }

    @Test
    @DisplayName("重复上传同一份题库：删旧+append新，不应造成旧版本堆积")
    void reuploadingSameSourceFileDoesNotAccumulateOldVersions() {
        BM25Retriever retriever = new BM25Retriever(10);

        // 第一次上传
        retriever.appendDocuments(List.of(
                RagDocument.builder().id("v1_q1").content("旧版本题目1").sourceFile("golang面试题.pdf").build(),
                RagDocument.builder().id("v1_q2").content("旧版本题目2").sourceFile("golang面试题.pdf").build()
        ));
        assertEquals(2, retriever.getDocuments().size());

        // 用户修改题库后重新上传同名文件：先删旧版本，再写新版本（模拟 WebSocketHandler 的覆盖流程）
        retriever.removeBySourceFile("golang面试题.pdf");
        retriever.appendDocuments(List.of(
                RagDocument.builder().id("v2_q1").content("新版本题目1").sourceFile("golang面试题.pdf").build()
        ));

        List<RagDocument> docs = retriever.getDocuments();
        assertEquals(1, docs.size(), "旧版本应被清理，只保留新版本，不应堆积");
        assertEquals("v2_q1", docs.get(0).getId());
    }

    @Test
    @DisplayName("removeBySourceFile 对不存在的文件/空索引应是安全的空操作")
    void removeBySourceFileIsNoOpWhenNothingMatches() {
        BM25Retriever retriever = new BM25Retriever(10);
        // 空索引调用不应抛异常
        assertDoesNotThrow(() -> retriever.removeBySourceFile("not-exist.pdf"));

        retriever.appendDocuments(List.of(
                RagDocument.builder().id("a1").content("题目A1").sourceFile("fileA.pdf").build()
        ));
        retriever.removeBySourceFile("not-exist.pdf");
        assertEquals(1, retriever.getDocuments().size(), "不匹配的文件名不应删除任何文档");
    }

    @Test
    @DisplayName("removeBySourceFile 之后索引应正确重建，被删文档不再能被检索到")
    void indexIsRebuiltAfterRemoveBySourceFile() {
        BM25Retriever retriever = new BM25Retriever(10);
        retriever.appendDocuments(List.of(
                RagDocument.builder().id("a1").content("Redis缓存穿透解决方案").sourceFile("fileA.pdf").build(),
                RagDocument.builder().id("b1").content("Kafka消息队列如何保证顺序消费").sourceFile("fileB.pdf").build()
        ));

        assertFalse(retriever.retrieve("缓存穿透").isEmpty(), "删除前应能检索到 fileA 的文档");

        retriever.removeBySourceFile("fileA.pdf");

        assertTrue(retriever.retrieve("缓存穿透").isEmpty(), "删除后不应再检索到 fileA 的文档");
        assertFalse(retriever.retrieve("消息队列").isEmpty(), "fileB 的文档应仍可正常检索");
    }
}
