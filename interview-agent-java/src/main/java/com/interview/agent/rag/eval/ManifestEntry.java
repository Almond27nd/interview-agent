/**
 */
package com.interview.agent.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * manifest.json 中每条题目的结构，与 Go 版本 manifestEntry 一致。
 *
 * content 存完整题目文本（BM25 索引需要），contentPreview 存截断摘要（方便人看）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ManifestEntry {

    private String id;

    /** 截断到 80 字符，方便人工标注时快速浏览 */
    @JsonProperty("content_preview")
    private String contentPreview;

    /** 完整题目文本（BM25 索引使用） */
    private String content;

    /** 参考答案 */
    private String reference;

    private String topic;
    private String difficulty;
    private String type;
    private List<String> skills;

    @JsonProperty("source_file")
    private String sourceFile;
}
