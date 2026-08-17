package com.contentops.common.finetune;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 模型微调相关配置属性（编排层，不执行实际 GPU 训练）。
 *
 * <p>通过 {@code contentops.fine-tune.*} 在 application.yml 中绑定，统一管理微调任务、
 * 数据集、评估、部署、A/B 测试等子模块的配置项。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   fine-tune:
 *     enabled: true
 *     data-dir: ${FINE_TUNE_DATA_DIR:./finetune-data}
 *     model-registry-dir: ${MODEL_REGISTRY_DIR:./model-registry}
 *     max-concurrent-tasks: 2
 *     default:
 *       epochs: 3
 *       batch-size: 8
 *       learning-rate: 0.0002
 *       warmup-ratio: 0.03
 *       weight-decay: 0.01
 *     lora:
 *       rank: 16
 *       alpha: 32
 *       dropout: 0.05
 *       target-modules: [q_proj, v_proj, k_proj, o_proj]
 *     evaluation:
 *       weights:
 *         accuracy: 0.30
 *         fluency: 0.20
 *         relevance: 0.20
 *         safety: 0.15
 *         consistency: 0.15
 *       safety-keywords: [暴力, 色情, 违法, 仇恨]
 *     deployment:
 *       default-mode: hybrid
 *       health-check-interval-seconds: 60
 *       max-retries: 3
 *       fallback-timeout-seconds: 10
 *       api:
 *         base-url: ${LLM_API_BASE_URL:https://api.openai.com}
 *         api-key: ${LLM_API_KEY:}
 *         model: gpt-4o
 *       local:
 *         ollama-url: ${OLLAMA_URL:http://localhost:11434}
 *         vllm-url: ${VLLM_URL:http://localhost:8000}
 *         model: qwen2.5:7b
 * }</pre>
 *
 * <p><b>设计理念：</b>本配置仅驱动编排层逻辑（任务状态机、数据集管理、评估调度、部署路由），
 * 不涉及实际 GPU 训练参数的下发。实际训练由外部训练集群（如 Ray、DeepSpeed）消费本框架
 * 产出的任务元数据后执行。
 *
 * @see ModelFineTuneManager
 * @see ModelEvaluationService
 * @see ModelDeploymentManager
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.fine-tune")
public class FineTuneProperties {

    /** 是否启用模型微调编排框架 */
    private boolean enabled = true;

    /** 数据集存储根目录（JSONL 训练数据落盘位置） */
    private String dataDir = "./finetune-data";

    /** 模型注册表目录（微调产物模型权重的元数据索引） */
    private String modelRegistryDir = "./model-registry";

    /** 最大并发微调任务数（超出则排队等待） */
    private int maxConcurrentTasks = 2;

    /** 任务超时时间（小时），超时后自动标记为 FAILED */
    private int taskTimeoutHours = 24;

    /** 默认训练超参数 */
    private DefaultHyperparams defaultParams = new DefaultHyperparams();

    /** LoRA 微调配置 */
    private LoraConfig lora = new LoraConfig();

    /** 评估相关配置 */
    private EvaluationConfig evaluation = new EvaluationConfig();

    /** 部署相关配置 */
    private DeploymentConfig deployment = new DeploymentConfig();

    // ════════════════════════════════════════════════════════════════
    // 默认训练超参数
    // ════════════════════════════════════════════════════════════════

    /**
     * 默认训练超参数配置。
     *
     * <p>作为新创建微调任务的初始超参数，可在任务级别被覆盖。
     */
    @Data
    public static class DefaultHyperparams {

        /** 训练轮数（epochs） */
        private int epochs = 3;

        /** 批大小（batch size） */
        private int batchSize = 8;

        /** 学习率 */
        private double learningRate = 0.0002;

        /** 预热比例（warmup ratio），占总体训练步数的比例 */
        private double warmupRatio = 0.03;

        /** 权重衰减（weight decay） */
        private double weightDecay = 0.01;

        /** 梯度累积步数 */
        private int gradientAccumulationSteps = 1;

        /** 最大序列长度（max sequence length） */
        private int maxSeqLength = 2048;

        /** 是否使用梯度检查点（gradient checkpointing）以节省显存 */
        private boolean gradientCheckpointing = true;
    }

    // ════════════════════════════════════════════════════════════════
    // LoRA 微调配置
    // ════════════════════════════════════════════════════════════════

    /**
     * LoRA（Low-Rank Adaptation）微调配置。
     *
     * <p>LoRA 通过在冻结的预训练权重旁注入低秩分解矩阵来实现高效微调，
     * 显著降低可训练参数量和显存占用。
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li><b>rank</b>：低秩矩阵的秩，常用 8/16/32/64。越大表达能力越强但参数越多</li>
     *   <li><b>alpha</b>：缩放因子，通常设为 rank 的 2 倍。控制 LoRA 更新的强度</li>
     *   <li><b>dropout</b>：LoRA 层的 dropout 比率，防止过拟合</li>
     *   <li><b>targetModules</b>：应用 LoRA 的注意力模块名称</li>
     * </ul>
     */
    @Data
    public static class LoraConfig {

        /** LoRA 秩（rank），决定低秩矩阵的大小 */
        private int rank = 16;

        /** LoRA alpha（缩放因子），实际缩放为 alpha / rank */
        private int alpha = 32;

        /** LoRA dropout 比率（0.0 - 1.0） */
        private double dropout = 0.05;

        /** 应用 LoRA 的目标模块（如 q_proj, v_proj, k_proj, o_proj） */
        private List<String> targetModules = List.of("q_proj", "v_proj");

        /** 是否将偏置参数纳入训练（none / all / lora_only） */
        private String bias = "none";

        /** LoRA 任务类型（CAUSAL_LM / SEQ_2_SEQ_LM / TOKEN_CLS） */
        private String taskType = "CAUSAL_LM";
    }

    // ════════════════════════════════════════════════════════════════
    // 评估配置
    // ════════════════════════════════════════════════════════════════

    /**
     * 模型评估配置。
     *
     * <p>定义五维评估的权重分布和安全性检测关键词。
     */
    @Data
    public static class EvaluationConfig {

        /** 各评估维度的权重（总和应为 1.0） */
        private Map<String, Double> weights = Map.of(
                "accuracy", 0.30,
                "fluency", 0.20,
                "relevance", 0.20,
                "safety", 0.15,
                "consistency", 0.15
        );

        /** 安全性检测关键词列表（命中即判定安全性不达标） */
        private List<String> safetyKeywords = List.of("暴力", "色情", "违法", "仇恨", "歧视");

        /** 一致性评估的重复采样次数（多次生成取方差） */
        private int consistencySamples = 3;

        /** 评估通过阈值（加权总分达到此值视为通过） */
        private double passThreshold = 70.0;

        /** 困惑度估算的滑动窗口大小（token 数） */
        private int perplexityWindowSize = 100;
    }

    // ════════════════════════════════════════════════════════════════
    // 部署配置
    // ════════════════════════════════════════════════════════════════

    /**
     * 模型部署配置。
     *
     * <p>支持 API 模式、本地部署模式、混合模式三种部署策略。
     */
    @Data
    public static class DeploymentConfig {

        /** 默认部署模式：api / local / hybrid */
        private String defaultMode = "hybrid";

        /** 健康检查间隔（秒） */
        private int healthCheckIntervalSeconds = 60;

        /** 最大重试次数 */
        private int maxRetries = 3;

        /** 降级超时时间（秒），本地模型超时后切换到 API */
        private int fallbackTimeoutSeconds = 10;

        /** API 模式配置 */
        private ApiConfig api = new ApiConfig();

        /** 本地部署模式配置 */
        private LocalConfig local = new LocalConfig();

        /** 灰度发布默认流量比例（0-100，表示新版本流量百分比） */
        private int defaultCanaryPercentage = 10;
    }

    /**
     * API 模式配置（调用 OpenAI / 通义千问等远程 API）。
     */
    @Data
    public static class ApiConfig {

        /** API 基础 URL */
        private String baseUrl = "https://api.openai.com";

        /** API 密钥（通过环境变量注入） */
        private String apiKey = "";

        /** 默认模型名称 */
        private String model = "gpt-4o";

        /** 请求超时时间（秒） */
        private int timeoutSeconds = 30;
    }

    /**
     * 本地部署模式配置（通过 Ollama / vLLM 接口）。
     */
    @Data
    public static class LocalConfig {

        /** Ollama 服务地址 */
        private String ollamaUrl = "http://localhost:11434";

        /** vLLM 服务地址 */
        private String vllmUrl = "http://localhost:8000";

        /** 本地默认模型名称 */
        private String model = "qwen2.5:7b";

        /** 优先使用本地推理引擎：ollama / vllm */
        private String preferredEngine = "ollama";

        /** 本地推理超时时间（秒） */
        private int timeoutSeconds = 60;
    }
}
