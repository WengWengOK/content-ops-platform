package com.contentops.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查与系统信息接口。
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "系统健康", description = "单体服务健康检查与运行状态查询")
public class HealthController {

    @GetMapping
    @Operation(summary = "健康检查", description = "返回单体服务的运行状态、版本信息和当前时间")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "mode", "monolithic",
                "version", "1.0.0",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/ready")
    @Operation(summary = "就绪检查", description = "用于 Kubernetes / 负载均衡的就绪探针")
    public ResponseEntity<Map<String, Object>> ready() {
        return ResponseEntity.ok(Map.of(
                "ready", true,
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
