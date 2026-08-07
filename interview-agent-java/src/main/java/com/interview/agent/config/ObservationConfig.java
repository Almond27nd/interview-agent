package com.interview.agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置。
 *
 * <p>Spring AI 的 {@code ChatModel.call()} 内部已内置 Observation，自动产生 LLM 调用 span
 *（含 {@code gen_ai.usage.input_tokens} / {@code gen_ai.usage.output_tokens} 属性）。
 * 本类只负责注册业务指标和确认 ObservationRegistry 可用。
 *
 * <p>业务指标埋点在各 Agent / Orchestrator 内部通过注入 {@link MeterRegistry} 实现，
 * 本类不定义具体指标——指标的语义和标签在埋点位置定义更清晰。
 */
@Configuration
public class ObservationConfig {

    /**
     * Spring Boot 自动配置会创建 ObservationRegistry，但如果 auto-config 未生效
     *（某些 Spring AI Alibaba 版本覆盖了默认配置），这里兜底创建一个。
     */
    @Bean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }
}
