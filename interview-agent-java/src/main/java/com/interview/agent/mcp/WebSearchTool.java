/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页搜索 MCP 工具（Router 升级）：为 {@code QuestionPlanner} 的出题 Agent 提供"专属题库"
 * 之外的第二个、异构的检索源——公开网络。有了它，出题 Agent 才真正具备"路由"的语义：需要在
 * 【专属题库】和【公开网络】两个来源之间自主判断该查哪一个，而不再是只围绕单一工具做
 * "检索 → 自评 → 重试 → fallback自生成"的纠正式（Corrective）闭环。
 * <p>
 * 检索源选型：用博查AI搜索（open.bochaai.com）而非更常见的 Tavily —— 项目里其余外部依赖
 * （DashScope、Milvus 等）都是国内可直连的服务，检索源保持同一选型原则，避免引入跨境访问
 * 不稳定的风险，这也是选型时需要考虑的一个真实工程约束。
 * <p>
 * 与 {@link GitHubTool} 同一套"可选工具"模式：未配置 API Key（{@code app.websearch.api-key}）
 * 时该类不会被注册为 Spring Bean（见 {@link ConditionalOnProperty}），{@code QuestionPlanner}
 * 里对应字段就是 null，出题 Agent 自动退化为只有题库一个来源，不影响主流程可用性。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.websearch", name = "api-key", matchIfMissing = false)
public class WebSearchTool {

    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public WebSearchTool(AppConfig appConfig) {
        this.apiKey = appConfig.getWebsearch().getApiKey();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private record CacheEntry(String result, long expiresAtMs) {}

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 真正打博查搜索 API 的部分：网络/接口层面的失败直接抛异常（区别于"搜到 0 条"），
     * 由调用方决定失败时该给模型什么提示。
     */
    private String searchRaw(String query, int topN) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "query", query,
                "count", topN,
                "summary", true,
                "freshness", "noLimit"
        ));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("网页搜索 API 返回状态码 " + response.statusCode() + "：" + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(200);
        if (code != 200) {
            throw new IllegalStateException("网页搜索 API 返回错误: " + root.path("msg").asText(""));
        }

        JsonNode items = root.path("data").path("webPages").path("value");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode item : items) {
            if (n >= topN) break;
            String title = item.path("name").asText("");
            String link = item.path("url").asText("");
            String summary = item.path("summary").asText(item.path("snippet").asText(""));
            if (title.isEmpty() || link.isEmpty()) continue;
            sb.append(String.format("- 标题: %s%n  链接: %s%n  摘要: %s%n", title, link, summary));
            n++;
        }
        return sb.toString();
    }

    /**
     * 暴露为 Spring AI ToolCallback，供出题 Agent 在题库未命中、且方向涉及新/冷门技术点时
     * 按需自主调用（是否调用、调用几次由模型的路由决策决定，不是代码强制的）。
     */
    public ToolCallback asRouterTool() {
        return FunctionToolCallback
                .builder("search_web", (SearchRequest req) -> {
                    String q = (req == null || req.query() == null) ? "" : req.query().trim();
                    if (q.isEmpty()) {
                        return "未提供检索关键词。";
                    }
                    String cacheKey = q.toLowerCase();
                    long now = System.currentTimeMillis();
                    CacheEntry cached = cache.get(cacheKey);
                    if (cached != null && cached.expiresAtMs() > now) {
                        log.info("[WebSearchTool] 命中本地缓存: {}", q);
                        return cached.result();
                    }

                    String result;
                    try {
                        String raw = searchRaw(q, 3);
                        result = raw.isEmpty()
                                ? "网络检索未获得有效结果，可换个关键词重试，或直接结合已有知识自行出题（source 填 llm）。"
                                : raw + "\n以上是公开网页检索结果，仅供你查证技术细节、辅助自行出题，"
                                        + "不要照搬网页内容当题目；出题后 source 填 \"web:<对应链接>\"。";
                    } catch (Exception e) {
                        log.warn("[WebSearchTool] 网页搜索异常: query={}, error={}", q, e.getMessage());
                        // 工具不可用（网络异常/触发限流）与"没搜到结果"分开提示：模型看到这句应停止
                        // 重试该工具，直接结合已有知识自行出题，而不是把"工具挂了"误判为该技术点无资料。
                        return "网页搜索工具当前不可用（网络异常或触发限流），请直接结合已有知识自行出题"
                                + "（source 填 llm），不要编造网页链接。";
                    }
                    // 只缓存调用成功的结果（无论是否命中），故障响应不缓存，避免短暂故障被当作长期结论
                    cache.put(cacheKey, new CacheEntry(result, now + CACHE_TTL.toMillis()));
                    return result;
                })
                .description("检索公开网页获取技术资料（标题、链接、摘要）。仅当题库检索未命中，"
                        + "且该考察方向涉及你把握不准的新/冷门技术细节时才调用，用于查证事实、辅助自行出题，"
                        + "不用于查找现成的面试题。")
                .inputType(SearchRequest.class)
                .build();
    }

    /** ReactAgent 调用本工具时的入参 */
    public record SearchRequest(String query) {}
}
