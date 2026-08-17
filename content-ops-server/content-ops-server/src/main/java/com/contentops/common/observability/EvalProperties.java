package com.contentops.common.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM-as-Judge 评测配置（contentops.evals.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.evals")
public class EvalProperties {

    /** 是否启用评测（流水线阶段完成后自动判分并落库） */
    private boolean enabled = true;

    /** 及格分（0-100），低于视为不通过 */
    private int threshold = 70;

    /** 是否启用门禁：低于阈值时标记该阶段评测失败（默认只记录不阻断） */
    private boolean gateEnabled = false;

    /** 判官模型档位：formatting（默认） */
    private String judgeModelTier = "formatting";
}
