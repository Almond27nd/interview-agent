/**
 */
package com.interview.agent.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 网页抓取 MCP 工具：用于抓取 JD 链接等网页内容。
 * 与 Go 版本 mcp/web_scraper.go 功能一致。
 *
 * 注意：Go 版本通过 Playwright MCP Server（stdio）抓取 JS 渲染页面。
 * Java 版本使用 HttpClient 进行基础抓取，对于需要 JS 渲染的页面建议用户直接粘贴内容。
 */
@Slf4j
@Component
public class WebScraperTool {

    private final HttpClient httpClient;

    public WebScraperTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 抓取网页文本内容
     */
    public String scrape(String url) {
        try {
            log.info("[WebScraper] 抓取网页: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; InterviewAgent/1.0)")
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[WebScraper] 抓取失败，状态码: {}", response.statusCode());
                return null;
            }

            // 简单的 HTML 标签清理
            String body = response.body();
            body = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "");
            body = body.replaceAll("<style[^>]*>[\\s\\S]*?</style>", "");
            body = body.replaceAll("<[^>]+>", " ");
            body = body.replaceAll("\\s+", " ").trim();

            log.info("[WebScraper] 抓取完成，内容长度: {}", body.length());
            return body;
        } catch (Exception e) {
            log.warn("[WebScraper] 抓取异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 暴露为 Spring AI ToolCallback（B3）：供 ReviewPlanner 在推荐官方文档/教程链接前，
     * 自主调用抓取一下确认链接可访问、内容确实相关，避免"看起来很像真链接但其实过期/404"
     * 的幻觉链接问题——此前本工具只在 WebLoader 里被 Java 代码直接调用，从未被暴露给模型自主调用。
     */
    public ToolCallback asToolCallback() {
        return FunctionToolCallback
                .builder("verify_url", (VerifyRequest req) -> {
                    String url = (req == null) ? null : req.url();
                    if (url == null || url.isBlank()) {
                        return "未提供有效链接。";
                    }
                    String content = scrape(url);
                    if (content == null) {
                        return "该链接抓取失败（可能已过期、无法访问或需要登录），不建议作为\"链接\"推荐，"
                                + "可以换一个来源，或者去掉 url 字段只保留文字性资源说明。";
                    }
                    String excerpt = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    return "链接可正常访问，页面内容摘要：" + excerpt;
                })
                .description("抓取并校验一个网页链接是否可正常访问、内容是否与推荐理由相符。"
                        + "在复习计划里推荐官方文档/教程链接前，建议先调用本工具确认链接真实有效，"
                        + "避免推荐一个看起来合理但实际已过期或 404 的资源。")
                .inputType(VerifyRequest.class)
                .build();
    }

    /** ReactAgent 调用本工具时的入参 */
    public record VerifyRequest(String url) {}
}
