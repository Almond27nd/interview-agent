/**
 */
package com.interview.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25Manager 测试：验证 deleteBySourceFile 能正确路由到对应用户的 BM25Retriever，
 * 且不影响其他用户的索引（用户间物理隔离）。
 */
class BM25ManagerTest {

    @Test
    @DisplayName("deleteBySourceFile 只影响目标用户，不影响其他用户的索引")
    void deleteBySourceFileOnlyAffectsTargetUser() {
        BM25Manager manager = new BM25Manager(10);

        manager.appendDocuments("userA", List.of(
                RagDocument.builder().id("a1").content("题目A1").sourceFile("f.pdf").build()
        ));
        manager.appendDocuments("userB", List.of(
                RagDocument.builder().id("b1").content("题目B1").sourceFile("f.pdf").build()
        ));

        manager.deleteBySourceFile("userA", "f.pdf");

        assertTrue(manager.retrieve("userA", "题目").isEmpty(), "userA 的 f.pdf 应已被删除");
        assertFalse(manager.retrieve("userB", "题目").isEmpty(), "userB 的索引不应受影响");
    }

    @Test
    @DisplayName("对不存在的用户调用 deleteBySourceFile 应是安全的空操作")
    void deleteBySourceFileIsNoOpForUnknownUser() {
        BM25Manager manager = new BM25Manager(10);
        assertDoesNotThrow(() -> manager.deleteBySourceFile("no-such-user", "f.pdf"));
    }

    @Test
    @DisplayName("模拟重复上传场景：先删旧文件再 append 新内容，检索结果只应命中新版本")
    void simulateReuploadFlow() {
        BM25Manager manager = new BM25Manager(10);

        manager.appendDocuments("user1", List.of(
                RagDocument.builder().id("v1").content("旧内容：数组越界异常处理").sourceFile("java题库.docx").build()
        ));
        assertFalse(manager.retrieve("user1", "越界").isEmpty());

        // 覆盖式更新：删旧 + append 新（与 WebSocketHandler.handleUploadQuestions 的流程一致）
        manager.deleteBySourceFile("user1", "java题库.docx");
        manager.appendDocuments("user1", List.of(
                RagDocument.builder().id("v2").content("新内容：空指针异常处理").sourceFile("java题库.docx").build()
        ));

        assertTrue(manager.retrieve("user1", "越界").isEmpty(), "旧内容不应再被检索到");
        assertFalse(manager.retrieve("user1", "空指针").isEmpty(), "新内容应可被检索到");
    }
}
