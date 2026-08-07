/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 客户端消息，JSON 字段与 Go 版本完全一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientMsg {
    private String type;        // chat / start_interview / answer / upload_questions / quit_interview / clarify_continue
    private String content;
    private String jd;
    private String resume;
    private String filename;
    private String data;        // base64 编码的文件内容
}
