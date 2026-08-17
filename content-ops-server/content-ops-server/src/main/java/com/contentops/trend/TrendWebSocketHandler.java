package com.contentops.trend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热点实时推送 WebSocket（原生协议，路径 /ws/trends）。
 *
 * <p>轮询检测到突发热点事件时调用 {@link #broadcast} 把 JSON 推给所有在线前端，
 * 前端原生 WebSocket 即可接收，无需 STOMP/SockJS 依赖。
 */
@Slf4j
@Component
public class TrendWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("[Trend-WS] 客户端接入，当前在线: {}", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("[Trend-WS] 客户端断开，当前在线: {}", sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[Trend-WS] 传输错误: {}", exception.getMessage());
        sessions.remove(session);
    }

    /** 向所有在线客户端广播一条 JSON 消息 */
    public void broadcast(String json) {
        if (json == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                }
            } catch (Exception e) {
                log.warn("[Trend-WS] 推送失败，移除会话: {}", e.getMessage());
                sessions.remove(session);
            }
        }
    }

    /** 当前在线客户端数（供状态接口/前端指示灯） */
    public int connectedCount() {
        return sessions.size();
    }
}
