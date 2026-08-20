/**
 */
package com.interview.agent.loader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 题库解析器：使用 LLM 将非结构化题库文本解析为结构化题目（与 Go 版本一致）
 * - 超过 maxSegmentLen 的文本按段落边界分段，逐段调用 LLM
 * - 校验题目字段合法性
 */
@Slf4j
@Component
public class QuestionParser {

    // 单段最大字符数：Go 版用 25000（其 HTTP 客户端无超时、可硬扛），但 qwen-plus 对超大单段
    // 会生成超长 JSON（单次 >10 分钟），在有超时的 Java 客户端下必然超时。调小到 8000 后每段题目
    // 更少、LLM 输出更短（单次几十秒），解析结果一致、只是分更多段。可用环境变量覆盖。
    private static final int MAX_SEGMENT_LEN =
            Integer.parseInt(System.getenv().getOrDefault("QUESTION_SEGMENT_LEN", "8000"));
    private static final Set<String> VALID_DIFFICULTIES = Set.of("easy", "medium", "hard");
    private static final Set<String> VALID_TYPES = Set.of("basic", "project", "design", "algorithm");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String QUESTION_PARSER_PROMPT = """
            你是一个面试题库解析专家。请从以下文本中提取所有面试题，并转换为结构化 JSON 格式。

            ## 输入文本
            %s

            ## 输出要求

            请输出一个 JSON 数组，每道题包含以下字段：

            [
              {
                "content": "题目文本（完整的面试问题）",
                "reference": "参考答案或答案要点",
                "difficulty": "easy 或 medium 或 hard",
                "type": "basic 或 project 或 design 或 algorithm",
                "skills": ["技能标签1", "技能标签2"]
              }
            ]

            ## 严格规则（必须遵守）

            1. difficulty 只能是以下三个值之一：easy、medium、hard。根据题目考察深度判断。
            2. type 只能是以下四个值之一：
               - basic：基础知识/概念题
               - project：项目经验/实战题
               - design：系统设计/架构题
               - algorithm：算法/数据结构题
            3. skills 必须是非空数组，填写该题考察的技术方向（如 "Go"、"MySQL"、"Redis"、"微服务"等）。
            4. content 必须是完整的题目文本，不要截断。
            5. reference 必须完整照搬原文中的答案内容，不得改写、缩写或重新组织。如果原文没有答案，填空字符串 ""。
            6. 只输出 JSON 数组，不要输出任何其他文字。

            请严格按照上述格式输出，不要添加额外字段，不要修改字段名称。""";

    private final ChatModel chatModel;

    public QuestionParser(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedQuestion {
        private String id;
        private String content;
        private String reference;
        private String difficulty;
        private String type;
        private List<String> skills;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseError {
        private int index;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseResult {
        private int total;
        private int success;
        private int failed;
        private List<ParseError> errors;
        private List<ParsedQuestion> questions;
    }

    /**
     * 解析非结构化题库文本为结构化题目
     */
    public ParseResult parseQuestionBank(String rawText) {
        List<ParsedQuestion> allRaw;

        if (rawText.length() <= MAX_SEGMENT_LEN) {
            allRaw = parseSegment(rawText);
        } else {
            List<String> segments = splitByQuestion(rawText, MAX_SEGMENT_LEN);
            log.info("[question_parser] 文本过长（{} 字符），拆分为 {} 段分别解析", rawText.length(), segments.size());
            allRaw = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                log.info("[question_parser] 解析第 {}/{} 段（{} 字符）...", i + 1, segments.size(), segments.get(i).length());
                try {
                    List<ParsedQuestion> parsed = parseSegment(segments.get(i));
                    log.info("[question_parser] 第 {} 段解析出 {} 道题", i + 1, parsed.size());
                    allRaw.addAll(parsed);
                } catch (Exception e) {
                    log.warn("[question_parser] 第 {} 段解析失败: {}，跳过", i + 1, e.getMessage());
                }
            }
        }

        List<ParsedQuestion> allParsed = new ArrayList<>();
        List<ParseError> allErrors = new ArrayList<>();

        long timestamp = Instant.now().getEpochSecond();
        for (int i = 0; i < allRaw.size(); i++) {
            int idx = i + 1;
            ParsedQuestion q = allRaw.get(i);
            String err = validateQuestion(q);
            if (err != null) {
                allErrors.add(ParseError.builder().index(idx).reason(err).build());
                continue;
            }
            q.setId(String.format("user_%d_%d", timestamp, idx));
            allParsed.add(q);
        }

        return ParseResult.builder()
                .total(allRaw.size())
                .success(allParsed.size())
                .failed(allErrors.size())
                .errors(allErrors)
                .questions(allParsed)
                .build();
    }

    /**
     * 对单段文本调用 LLM 解析
     */
    private List<ParsedQuestion> parseSegment(String text) {
        String prompt = String.format(QUESTION_PARSER_PROMPT, text);
        ChatResponse response = chatModel.call(new Prompt(prompt));
        String content = extractJSONArray(response.getResult().getOutput().getText());

        try {
            return objectMapper.readValue(content, new TypeReference<>() {});
        } catch (Exception e) {
            // 尝试修复被截断的 JSON
            String repaired = tryRepairJSONArray(content);
            if (repaired != null) {
                try {
                    List<ParsedQuestion> result = objectMapper.readValue(repaired, new TypeReference<>() {});
                    log.info("[question_parser] JSON 被截断，自动修复成功（恢复 {} 道题）", result.size());
                    return result;
                } catch (Exception ignored) {}
            }
            throw new RuntimeException("question_parser: json parse failed: " + e.getMessage());
        }
    }

    /**
     * 尝试修复被截断的 JSON 数组
     */
    private String tryRepairJSONArray(String content) {
        for (int i = content.length() - 1; i >= 0; i--) {
            if (content.charAt(i) == '}') {
                String candidate = content.substring(0, i + 1).replaceAll("[,\\s]+$", "") + "]";
                int start = candidate.indexOf('[');
                if (start >= 0) {
                    return candidate.substring(start);
                }
                break;
            }
        }
        return null;
    }

    /**
     * 校验单道题的字段合法性
     */
    private String validateQuestion(ParsedQuestion q) {
        if (q.getContent() != null) q.setContent(q.getContent().trim());
        if (q.getReference() != null) q.setReference(q.getReference().trim());
        if (q.getDifficulty() != null) q.setDifficulty(q.getDifficulty().trim().toLowerCase());
        if (q.getType() != null) q.setType(q.getType().trim().toLowerCase());

        if (q.getContent() == null || q.getContent().length() < 10) {
            return String.format("content 长度不足（仅 %d 字符，要求 ≥ 10）",
                    q.getContent() == null ? 0 : q.getContent().length());
        }
        if (q.getReference() == null || q.getReference().length() < 10) {
            return String.format("reference 长度不足（仅 %d 字符，要求 ≥ 10）",
                    q.getReference() == null ? 0 : q.getReference().length());
        }
        if (!VALID_DIFFICULTIES.contains(q.getDifficulty())) {
            return String.format("difficulty 值 \"%s\" 不在合法枚举中（仅允许 easy/medium/hard）", q.getDifficulty());
        }
        if (!VALID_TYPES.contains(q.getType())) {
            return String.format("type 值 \"%s\" 不在合法枚举中（仅允许 basic/project/design/algorithm）", q.getType());
        }
        if (q.getSkills() == null || q.getSkills().isEmpty()) {
            return "skills 为空数组";
        }
        List<String> cleaned = q.getSkills().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            return "skills 全部为空字符串";
        }
        q.setSkills(cleaned);
        return null;
    }

    /**
     * 从 LLM 输出中提取 JSON 数组
     */
    static String extractJSONArray(String text) {
        String trimmed = text.trim();

        // 直接以 [ 开头
        if (trimmed.startsWith("[")) {
            int end = trimmed.lastIndexOf(']');
            if (end > 0) {
                return trimmed.substring(0, end + 1);
            }
            return trimmed;
        }

        // markdown 代码块
        int idx = text.indexOf("```json");
        if (idx >= 0) {
            int start = idx + 7;
            int end = text.indexOf("```", start);
            if (end >= 0) {
                return text.substring(start, end).trim();
            }
        }
        idx = text.indexOf("```");
        if (idx >= 0) {
            int start = idx + 3;
            int nl = text.indexOf('\n', start);
            if (nl >= 0) start = nl + 1;
            int end = text.indexOf("```", start);
            if (end >= 0) {
                return text.substring(start, end).trim();
            }
        }

        // 兜底：查找 JSON 数组起止
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 按题目标题边界分段
     */
    static List<String> splitByQuestion(String text, int maxLen) {
        String[] lines = text.split("\n");

        List<Integer> splitPoints = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("### ") || trimmed.startsWith("## ")) {
                splitPoints.add(i);
            }
        }

        if (splitPoints.isEmpty()) {
            return splitByParagraph(text, maxLen);
        }

        // 按标题切割成独立题目块
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;
            String block = String.join("\n", Arrays.copyOfRange(lines, start, end)).trim();
            if (!block.isEmpty()) {
                blocks.add(block);
            }
        }

        // 合并为不超过 maxLen 的段
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String b : blocks) {
            if (current.length() > 0 && current.length() + b.length() + 2 > maxLen) {
                segments.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(b);
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        if (segments.isEmpty() && !text.isEmpty()) {
            segments.add(text);
        }
        return segments;
    }

    /**
     * 按段落边界分段（回退方案）
     */
    static List<String> splitByParagraph(String text, int maxLen) {
        String[] paragraphs = text.split("\n\n");
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            p = p.trim();
            if (p.isEmpty()) continue;

            if (current.length() > 0 && current.length() + p.length() + 2 > maxLen) {
                segments.add(current.toString());
                current = new StringBuilder();
            }

            if (current.length() == 0 && p.length() > maxLen) {
                segments.add(p);
                continue;
            }

            if (current.length() > 0) current.append("\n\n");
            current.append(p);
        }

        if (current.length() > 0) {
            segments.add(current.toString());
        }
        if (segments.isEmpty() && !text.isEmpty()) {
            segments.add(text);
        }
        return segments;
    }
}
