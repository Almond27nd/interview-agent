/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.config;

import org.eclipse.jetty.client.HttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JettyClientHttpRequestFactory;

import java.time.Duration;

/**
 * 放大 DashScope 同步 HTTP 调用的超时。
 *
 * <p>Spring AI Alibaba 1.1.2.0（classpath 带 jetty-client）下，长文本 LLM 调用（题库解析 /
 * RAG 评估）会被多层默认超时掐断，且 {@code spring.ai.dashscope.read-timeout} 进了 Environment
 * 却没真正绑定到框架创建 RestClient 的 customizer：
 * <ul>
 *   <li>Jetty request total timeout 默认 10s；</li>
 *   <li>Jetty connection idle timeout 默认 30s（token 间隔一旦超过就断）。</li>
 * </ul>
 * 换 JDK HttpClient 又会与 dashscope 出现 TLS 握手失败，因此仍用 Jetty，但显式构造一个
 * idle/connect/read 都放大的 Jetty {@link HttpClient}。
 *
 * <p>通过 {@link AutoConfigureAfter} 让本配置在 DashScopeAutoConfiguration 之后注册，使本
 * {@link RestClientCustomizer} 最后执行，覆盖框架自带 customizer 的 requestFactory。仅影响
 * 同步 RestClient 路径（chat call / 题库解析 / 评估）。
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAutoConfiguration")
public class RagHttpClientAutoConfig {

    /** 读/空闲超时（秒），可用环境变量 DASHSCOPE_READ_TIMEOUT 覆盖（默认 300）。 */
    private static final long TIMEOUT_SECONDS =
            Long.parseLong(System.getenv().getOrDefault("DASHSCOPE_READ_TIMEOUT", "600"));

    /** 自定义 Jetty HttpClient：idle 300s、connect 30s（由 Spring 管理生命周期，销毁时 stop）。 */
    @Bean(destroyMethod = "stop")
    public HttpClient dashScopeJettyHttpClient() throws Exception {
        HttpClient client = new HttpClient();
        client.setIdleTimeout(TIMEOUT_SECONDS * 1000L);
        client.setConnectTimeout(30_000L);
        client.start();
        return client;
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public RestClientCustomizer dashScopeLargeTimeoutRestClientCustomizer(HttpClient dashScopeJettyHttpClient) {
        JettyClientHttpRequestFactory factory = new JettyClientHttpRequestFactory(dashScopeJettyHttpClient);
        factory.setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS));
        return builder -> builder.requestFactory(factory);
    }
}
