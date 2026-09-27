package com.example.learningassistant.hall.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 对话厅 WebSocket 注册：端点 /ws/hall（不在 /api/** 拦截范围内，
 * 鉴权由 HallHandshakeInterceptor 在握手时完成）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class HallWebSocketConfig implements WebSocketConfigurer {

    private final HallWebSocketHandler hallWebSocketHandler;
    private final HallHandshakeInterceptor hallHandshakeInterceptor;

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 死连接（断网/杀进程没发 close 帧）最多 90s 被容器清理；在线客户端每 25s 心跳保活
        container.setMaxSessionIdleTimeout(90_000L);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hallWebSocketHandler, "/ws/hall")
                .addInterceptors(hallHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
