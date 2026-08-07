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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GitHub MCP 工具：搜索 GitHub 仓库，用于复习计划推荐开源项目。
 * 与 Go 版本 mcp/github_tool.go 功能一致。
 *
 * B4 健壮性加固：
 * 1. 短期本地缓存（同一关键词 {@link #CACHE_TTL} 内直接返回缓存结果），避免高频关键词
 *    （如"Redis"、"Spring Boot"）每次都重新打 GitHub API，容易撞到未认证/低配额限流；
 * 2. 质量过滤：排除 fork 仓库、过滤长期不维护（{@link #STALE_YEARS} 年内无更新）的项目，
 *    避免推荐"star 数好看但早就没人维护"的死项目；
 * 3. 区分"没搜到结果"和"工具当前不可用"两种情况，返回给模型的文案不同——
 *    模型可以据此判断要不要换个关键词重试，还是直接放弃工具、只用已知资源。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.github", name = "token", matchIfMissing = false)
public class GitHubTool {

    private static final Duration CACHE_TTL = Duration.ofMinutes(8);
    private static final int STALE_YEARS = 2;

    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GitHubTool(AppConfig appConfig) {
        this.token = appConfig.getGithub().getToken();
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /** 搜索结果的结构化表示，便于在过滤/格式化阶段访问 fork/更新时间等字段 */
    private record RepoInfo(String fullName, int stars, String url, String description,
                             boolean fork, String pushedAt) {}

    private record CacheEntry(String result, long expiresAtMs) {}

    /**
     * 真正打 GitHub API 的部分：网络/接口层面的失败直接抛异常（区别于"搜到 0 条"），
     * 由调用方决定失败时该给模型什么提示。
     */
    private List<RepoInfo> searchRepositoriesRaw(String query, int maxResults) throws Exception {
        String url = String.format(
                "https://api.github.com/search/repositories?q=%s&sort=stars&order=desc&per_page=%d",
                java.net.URLEncoder.encode(query, "UTF-8"), maxResults);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json");

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "token " + token);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API 返回状态码 " + response.statusCode()
                    + "（可能未认证/触发限流）");
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode items = root.get("items");
        List<RepoInfo> result = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                boolean fork = item.has("fork") && item.get("fork").asBoolean();
                String pushedAt = item.has("pushed_at") && !item.get("pushed_at").isNull()
                        ? item.get("pushed_at").asText() : null;
                String desc = item.has("description") && !item.get("description").isNull()
                        ? item.get("description").asText() : "无描述";
                result.add(new RepoInfo(item.get("full_name").asText(), item.get("stargazers_count").asInt(),
                        item.get("html_url").asText(), desc, fork, pushedAt));
            }
        }
        return result;
    }

    /**
     * 质量过滤：排除 fork、排除长期不维护的项目。
     * 如果严格过滤后一个不剩（比如全是 fork，或者这个关键词下的结果全部很旧），
     * 退化为只过滤 fork，保证不会因为过滤太狠而"过滤没了"。
     */
    private List<RepoInfo> filterQuality(List<RepoInfo> repos) {
        LocalDate cutoff = LocalDate.now().minusYears(STALE_YEARS);

        List<RepoInfo> nonFork = repos.stream().filter(r -> !r.fork()).collect(Collectors.toList());

        List<RepoInfo> fresh = nonFork.stream()
                .filter(r -> {
                    if (r.pushedAt() == null || r.pushedAt().length() < 10) {
                        return true; // 拿不到更新时间，不因此排除
                    }
                    try {
                        return !LocalDate.parse(r.pushedAt().substring(0, 10)).isBefore(cutoff);
                    } catch (Exception e) {
                        return true;
                    }
                })
                .collect(Collectors.toList());

        return fresh.isEmpty() ? nonFork : fresh;
    }

    private String formatResults(List<RepoInfo> repos) {
        StringBuilder sb = new StringBuilder();
        for (RepoInfo r : repos) {
            sb.append(String.format("- **%s**（⭐ %d）：%s%n  链接：%s%n",
                    r.fullName(), r.stars(), r.description(), r.url()));
        }
        return sb.toString();
    }

    /** 搜索 GitHub 仓库（保留原始方法名，兼容旧调用方式；内部已接入缓存+质量过滤） */
    public String searchRepositories(String query, int maxResults) {
        try {
            List<RepoInfo> filtered = filterQuality(searchRepositoriesRaw(query, maxResults));
            return filtered.isEmpty() ? null : formatResults(filtered.stream().limit(maxResults).collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("[GitHub] 搜索异常: {}", e.getMessage());
            return null;
        }
    }

    /** 暴露为 Spring AI ToolCallback，供 ReactAgent 在生成复习计划时按需自主调用 */
    public ToolCallback asToolCallback() {
        return FunctionToolCallback
                .builder("search_github_repos", (GithubSearchRequest req) -> {
                    String q = (req == null || req.query() == null) ? "" : req.query().trim();
                    if (q.isEmpty()) {
                        return "未提供搜索关键词。";
                    }
                    String cacheKey = q.toLowerCase();
                    long now = System.currentTimeMillis();
                    CacheEntry cached = cache.get(cacheKey);
                    if (cached != null && cached.expiresAtMs() > now) {
                        log.info("[GitHub] 命中本地缓存: {}", q);
                        return cached.result();
                    }

                    String result;
                    try {
                        List<RepoInfo> filtered = filterQuality(searchRepositoriesRaw(q + " stars:>100", 8));
                        List<RepoInfo> top = filtered.stream().limit(5).collect(Collectors.toList());
                        result = top.isEmpty()
                                ? "未找到符合质量要求的开源项目（可能该关键词过窄），可以换一个更宽泛/更精确的关键词再试一次。"
                                : formatResults(top);
                    } catch (Exception e) {
                        log.warn("[GitHub] 搜索异常: {}", e.getMessage());
                        // 工具不可用（网络异常/限流）与"没搜到结果"分开提示：模型看到这句应停止重试该工具，
                        // 直接使用已知的可靠资源，而不是把"工具挂了"误判为"这个关键词没有相关项目"。
                        return "GitHub 搜索工具当前不可用（网络异常或触发限流），请仅使用你已知的可靠资源，不要编造链接。";
                    }
                    // 只缓存"工具调用成功"的结果（无论是否命中），故障响应不缓存，避免短暂故障被当作长期结论
                    cache.put(cacheKey, new CacheEntry(result, now + CACHE_TTL.toMillis()));
                    return result;
                })
                .description("根据技术关键词搜索 GitHub 上 star 数较多、非 fork、近期仍在维护的开源项目与教程，"
                        + "返回项目清单（名称、star 数、链接、简介）。为候选人推荐真实可用的学习项目时调用，关键词用英文技术词。")
                .inputType(GithubSearchRequest.class)
                .build();
    }

    /** ReactAgent 调用本工具时的入参 */
    public record GithubSearchRequest(String query) {}
}
