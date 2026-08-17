package com.contentops.common.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP HTTP 传输端点（streamable HTTP 的 JSON 响应形态）：
 * POST /mcp 处理 JSON-RPC；GET /mcp 为 SSE 心跳（客户端连接保活）。
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class McpController {

    private final McpProtocolService mcpProtocolService;

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mcp(@RequestBody(required = false) String body) {
        String response = mcpProtocolService.process(body);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * streamable HTTP 的 SSE 通道：发送 endpoint 事件后持续心跳，客户端断开即停止。
     */
    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter mcpSse() {
        SseEmitter emitter = new SseEmitter(0L);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        try {
            emitter.send(SseEmitter.event().name("endpoint").data("/mcp"));
        } catch (Exception ignored) {
            // client gone
        }
        scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").comment("keep-alive"));
            } catch (Exception e) {
                scheduler.shutdownNow();
            }
        }, 15, 15, TimeUnit.SECONDS);
        emitter.onCompletion(scheduler::shutdownNow);
        emitter.onTimeout(scheduler::shutdownNow);
        emitter.onError(e -> scheduler.shutdownNow());
        return emitter;
    }
}
