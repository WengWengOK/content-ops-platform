package com.contentops.common.streaming;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式响应配置属性（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>通过 {@code contentops.streaming.*} 在 application.yml 中绑定，控制
 * 哪些 Agent 阶段启用流式响应（SSE / WebSocket 增量推送）。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   streaming:
 *     enabled: false
 *     stages:
 *       - content-creation
 * }</pre>
 *
 * @see StreamingSupport
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.streaming")
public class StreamingProperties {

    /** 是否启用流式响应（默认关闭，需要 StreamingChatModel 支持） */
    private boolean enabled = false;

    /** 启用流式响应的阶段列表（使用 AgentStage 的 code，如 "content-creation"） */
    private List<String> stages = new ArrayList<>();

    /** 流式响应缓冲区大小（字节），超过此大小时 flush 到客户端 */
    private int bufferSize = 256;
}
