/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private MilvusProperties milvus = new MilvusProperties();
    private JwtProperties jwt = new JwtProperties();
    private GitHubProperties github = new GitHubProperties();
    private AuthProperties auth = new AuthProperties();
    private WebSearchProperties websearch = new WebSearchProperties();

    @Data
    public static class MilvusProperties {
        private String host = "localhost";
        private int port = 19530;
    }

    @Data
    public static class JwtProperties {
        private String secret = "interview-agent-default-secret";
        private long expiration = 86400000; // 24 hours
    }

    @Data
    public static class GitHubProperties {
        private String token = "";
    }

    @Data
    public static class AuthProperties {
        private boolean enabled = true;
    }

    /** Router 升级：网页搜索（博查AI搜索）配置，key 为空则 {@code WebSearchTool} 不会被注册为 Bean */
    @Data
    public static class WebSearchProperties {
        private String apiKey = "";
    }
}
