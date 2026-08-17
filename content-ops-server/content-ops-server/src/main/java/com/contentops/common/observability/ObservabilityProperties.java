package com.contentops.common.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 可观测性配置（contentops.observability.llm.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.observability.llm")
public class ObservabilityProperties {

    /** 是否记录 LLM trace（关闭后无任何写入开销） */
    private boolean enabled = true;

    /** trace 保留天数，超期由每日清理任务删除 */
    private int retentionDays = 7;

    /** 各模型成本估算（美元 / 百万 token），用于成本大盘 */
    private Map<String, Pricing> pricing = new HashMap<>();

    @Data
    public static class Pricing {
        private double inputPerMillion = 0.27;
        private double outputPerMillion = 1.10;
    }
}
