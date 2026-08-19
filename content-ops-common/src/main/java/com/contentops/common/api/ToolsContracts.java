package com.contentops.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Phase3 服务化：Tools Service 对外契约 DTO（RAG 检索/趋势/热点查询统一请求）。
 * 用于 Worker → Tools 的内部 HTTP 调用（MCP 协议已就绪，REST 是简化兜底）。
 */
public class ToolsContracts {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RagSearchRequest implements Serializable {
        private String query;
        private String accountId;
        private String niche;
        /** 期望返回 top-K */
        @Builder.Default
        private int topK = 5;
        /** 最低相似度分（0-1，默认 0.4） */
        @Builder.Default
        private double minScore = 0.4;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RagSearchResponse implements Serializable {
        private boolean success;
        private List<Map<String, Object>> results;
        private String errorMessage;
        /** 耗时/召回数等调试信息 */
        private Map<String, Object> debug;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrendsRequest implements Serializable {
        /** 所属平台：douyin/xhs/bilibili 等 */
        private String platform;
        /** 垂直领域：美食/游戏/财经...（可空=全量） */
        private String niche;
        @Builder.Default
        private int limit = 20;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrendsResponse implements Serializable {
        private boolean success;
        private List<Map<String, Object>> trends;
        private String errorMessage;
    }

    /** Agent 输出 → 知识库写入请求（供 RemoteToolsGateway 调用） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RagIngestRequest implements Serializable {
        /** 文本内容（会被向量化后存入 PGVector） */
        private String content;
        /** 内容类型：article / topic_plan / analysis_report / competitor_data */
        private String type;
        /** 来源 Agent stage code */
        private String agent;
        /** 账号垂直领域 */
        private String niche;
        /** 工作流 ID */
        private String workflowId;
        /** 账号 ID */
        private String accountId;
    }

    /** Agent 输出 → 知识库写入响应 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RagIngestResponse implements Serializable {
        private boolean success;
        private String errorMessage;
    }
}
