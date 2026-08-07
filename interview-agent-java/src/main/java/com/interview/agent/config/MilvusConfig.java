/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MilvusConfig {

    private final AppConfig appConfig;

    @Bean
    public MilvusClientV2 milvusClient() {
        String uri = String.format("http://%s:%d",
                appConfig.getMilvus().getHost(),
                appConfig.getMilvus().getPort());
        log.info("[Milvus] 连接: {}", uri);

        ConnectConfig config = ConnectConfig.builder()
                .uri(uri)
                .build();
        return new MilvusClientV2(config);
    }
}
