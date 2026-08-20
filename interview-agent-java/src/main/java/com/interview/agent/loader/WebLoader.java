/**
 */
package com.interview.agent.loader;

import com.interview.agent.mcp.WebScraperTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 网页内容加载器（与 Go 版本一致）
 * - 通过 HTTP 抓取网页
 * - 用 LLM 从网页内容中提取 JD 正文
 */
@Slf4j
@Component
public class WebLoader {

    private final WebScraperTool webScraperTool;
    private final ChatModel chatModel;

    public WebLoader(WebScraperTool webScraperTool, ChatModel chatModel) {
        this.webScraperTool = webScraperTool;
        this.chatModel = chatModel;
    }

    /**
     * 抓取网页内容
     */
    public String fetchURL(String url) {
        return webScraperTool.scrape(url);
    }

    /**
     * 通过抓取网页 + LLM 提取 JD 正文
     */
    public String extractJDFromURL(String url) {
        String rawText = fetchURL(url);
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }

        // 截断过长的网页内容（避免超出 token 限制）
        if (rawText.length() > 10000) {
            rawText = rawText.substring(0, 10000);
        }

        String prompt = """
                以下是通过浏览器从招聘网页抓取的页面内容（accessibility snapshot 格式），其中包含职位描述（JD）以及一些无关的导航、广告等内容。
                请提取出完整的职位描述部分，包括：岗位名称、工作职责、任职要求、技术栈要求等。
                只输出 JD 正文，不要输出其他内容。

                原始页面内容：
                """ + rawText;

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String content = response.getResult().getOutput().getText();

            // 检测 LLM 是否真的提取到了有效 JD
            String lower = content.toLowerCase();
            String[] invalidMarkers = {"无法提取", "无法识别", "未包含", "不包含", "没有找到", "错误页", "err_"};
            for (String marker : invalidMarkers) {
                if (lower.contains(marker)) {
                    throw new RuntimeException("loader: 该链接未包含有效的职位描述，请直接粘贴 JD 文本或上传文件");
                }
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // LLM 提取失败，退化为返回原始文本
            log.warn("[WebLoader] LLM 提取 JD 失败，返回原始文本: {}", e.getMessage());
            return rawText;
        }
    }
}
