package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册热点实时推送 WebSocket 端点（/ws/trends）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class TrendWebSocketConfig implements WebSocketConfigurer {

    private final TrendWebSocketHandler trendWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(trendWebSocketHandler, "/ws/trends")
                .setAllowedOrigins("*");
    }
}
