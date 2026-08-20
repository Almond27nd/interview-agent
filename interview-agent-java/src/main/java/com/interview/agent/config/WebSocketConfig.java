/**
 */
package com.interview.agent.config;

import com.interview.agent.handler.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketHandler webSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws")
                .setAllowedOrigins("*");
    }

    /**
     * 放大 WebSocket 消息缓冲区与空闲超时。
     *
     * <p>题库文件（MD/PDF/TXT）经 base64 编码后通过 WebSocket 的 upload_questions 消息上传，
     * 体积常达几百 KB 到数 MB，而 Spring/Tomcat 默认单条文本消息上限仅 8KB——超限会导致后端
     * 直接关闭连接，前端表现为上传失败、控制台刷 EPIPE。这里放大到 32MB 以支持大题库上传，
     * 同时把空闲超时设为 10 分钟，避免面试长连接在等待用户作答时被断开。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(32 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(32 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(600_000L);
        return container;
    }
}
