package com.contentops.common.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 输出持久化配置（v1.2.0 RAG 知识库 P0 遗留项）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.output-persistence}：
 * <pre>
 * contentops:
 *   output-persistence:
 *     enabled: true
 *     formats: [json, markdown]
 *     base-dir: ${FILE_OUTPUT_DIR:./content-outputs}
 * </pre>
 *
 * <p>该配置驱动 {@link AgentOutputPersistence}：将各 Agent 的结构化输出自动保存为
 * Markdown 与 JSON 文件，供后续检索、分享、归档，并为 RAG 知识库沉淀历史产出。
 *
 * <p><b>与 FileTools 的关系：</b>实际文件写入委托给 {@link FileTools}（含路径沙箱与扩展名校验），
 * 文件最终落在 {@code contentops.file-tools.base-dir} 目录下。本配置中的 {@link #baseDir}
 * 用于在不依赖 FileTools 的直写兜底场景，以及组织子目录结构；默认与 FileTools 保持一致。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.output-persistence")
public class AgentOutputPersistenceProperties {

    /** 是否启用 Agent 输出自动持久化 */
    private boolean enabled = true;

    /** 输出格式：支持 "json" 与 "markdown"，可同时输出两者 */
    private List<String> formats = List.of("json", "markdown");

    /**
     * 输出根目录（兜底直写时使用，并通过环境变量 {@code FILE_OUTPUT_DIR} 覆盖）。
     * <p>默认与 FileTools 的 base-dir 一致，避免双份配置漂移。
     */
    private String baseDir = "./content-outputs";

    /** 是否在保存成功后同步入库到 RAG 知识库（便于历史产出被语义检索） */
    private boolean ingestToKnowledgeBase = false;
}
