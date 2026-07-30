package com.contentops.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Content Ops 单体服务启动类。
 *
 * <p>合并原微服务架构中的 orchestrator、common 及各 agent 模块为单一可部署单元。
 * 扫描范围覆盖所有后端代码包：server、common、orchestrator、topic、content、image、publish、analysis、optimize。
 */
@SpringBootApplication(scanBasePackages = {
        "com.contentops.server",
        "com.contentops.common",
        "com.contentops.orchestrator",
        "com.contentops.topic",
        "com.contentops.content",
        "com.contentops.image",
        "com.contentops.publish",
        "com.contentops.analysis",
        "com.contentops.optimize"
})
public class ContentOpsServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentOpsServerApplication.class, args);
    }
}
