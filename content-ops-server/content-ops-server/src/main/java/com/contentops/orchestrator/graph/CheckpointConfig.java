package com.contentops.orchestrator.graph;

import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangGraph4j CheckpointSaver 配置。
 *
 * <p>当前使用 {@link MemorySaver}（内存检查点），适用于单节点部署。
 * 后续可升级为 {@code RedisSaver}（需额外引入 langgraph4j-redis-saver 依赖 + Redisson）
 * 以支持多节点部署的检查点共享和断点恢复。
 *
 * <p>CheckpointSaver 在每次节点执行后自动保存状态快照，
 * 当图因 {@code interruptBefore} 暂停时，可通过 {@code resume()} 从检查点恢复执行。
 */
@Configuration
public class CheckpointConfig {

    @Bean
    public BaseCheckpointSaver checkpointSaver() {
        return new MemorySaver();
    }
}
