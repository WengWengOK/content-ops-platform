package com.contentops.common.finetune;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型微调管理器（编排层，不执行实际 GPU 训练）。
 *
 * <p>封装微调任务的创建、状态流转、数据集管理、超参数配置和评估调度，
 * 将任务元数据持久化到数据库，供外部训练集群消费。实际训练过程由独立的
 * 训练服务（如 Ray / DeepSpeed）拉取任务记录后执行，本管理器仅负责编排。
 *
 * <h3>任务生命周期</h3>
 * <pre>
 * CREATED → DATA_PREPARING → TRAINING → EVALUATING → DEPLOYED → ARCHIVED
 *     ↓          ↓              ↓           ↓
 *   FAILED    FAILED         FAILED      FAILED
 * </pre>
 * <ul>
 *   <li><b>CREATED</b>：任务已创建，等待数据准备</li>
 *   <li><b>DATA_PREPARING</b>：数据集验证与格式转换中</li>
 *   <li><b>TRAINING</b>：训练进行中（由外部训练集群执行）</li>
 *   <li><b>EVALUATING</b>：训练完成，评估模型质量</li>
 *   <li><b>DEPLOYED</b>：评估通过并已部署</li>
 *   <li><b>ARCHIVED</b>：已归档（被新版本替代或手动归档）</li>
 *   <li><b>FAILED</b>：任一阶段失败（记录错误信息）</li>
 * </ul>
 *
 * <h3>LoRA 微调支持</h3>
 * <p>通过 {@link FineTuneProperties.LoraConfig} 配置 LoRA 参数（rank, alpha, dropout, targetModules），
 * 任务记录中保存完整的超参数快照，确保训练可复现。
 *
 * <h3>数据集管理</h3>
 * <p>支持 JSONL 格式训练数据的注册、验证、去重和落盘。数据集通过 {@link FineTuneDataset}
 * 封装，支持 Instruction / Chat / Preference 三种格式。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建微调任务
 * FineTuneTask task = manager.createTask(
 *     "qwen2.5-7b",
 *     "content-ops-lora-v1",
 *     FineTuneDataset.of("train-set", Format.INSTRUCTION, samples)
 * );
 *
 * // 推进状态
 * manager.transitionTo(task.taskId(), FineTuneTaskStatus.TRAINING);
 *
 * // 记录训练指标
 * manager.recordMetrics(task.taskId(), FineTuneMetrics.of(0.85, 0.92, 1.2));
 * }</pre>
 *
 * @see FineTuneProperties
 * @see FineTuneDataset
 * @see ModelEvaluationService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFineTuneManager {

    private final FineTuneProperties properties;
    private final JdbcTemplate jdbcTemplate;

    /** 内存任务缓存（数据库持久化的同时维护内存索引，加速查询） */
    private final Map<String, FineTuneTask> taskCache = new ConcurrentHashMap<>();

    /** 注册的数据集（按名称索引） */
    private final Map<String, FineTuneDataset> datasetRegistry = new ConcurrentHashMap<>();

    /** Jackson ObjectMapper，用于超参数和指标序列化 */
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    // ════════════════════════════════════════════════════════════════
    // 任务状态枚举
    // ════════════════════════════════════════════════════════════════

    /**
     * 微调任务生命周期状态。
     */
    public enum FineTuneTaskStatus {
        /** 已创建，等待数据准备 */
        CREATED,
        /** 数据准备中（验证、格式转换、去重） */
        DATA_PREPARING,
        /** 训练进行中 */
        TRAINING,
        /** 评估中 */
        EVALUATING,
        /** 已部署 */
        DEPLOYED,
        /** 已归档 */
        ARCHIVED,
        /** 失败 */
        FAILED;

        /**
         * 判断当前状态是否可以流转到目标状态。
         *
         * @param target 目标状态
         * @return true 表示状态流转合法
         */
        public boolean canTransitionTo(FineTuneTaskStatus target) {
            return switch (this) {
                case CREATED -> target == DATA_PREPARING || target == FAILED;
                case DATA_PREPARING -> target == TRAINING || target == FAILED;
                case TRAINING -> target == EVALUATING || target == FAILED;
                case EVALUATING -> target == DEPLOYED || target == FAILED || target == ARCHIVED;
                case DEPLOYED -> target == ARCHIVED;
                case ARCHIVED, FAILED -> false;
            };
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 微调任务记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 微调任务记录（可序列化存储到数据库）。
     *
     * @param taskId          任务唯一 ID
     * @param taskName        任务名称
     * @param baseModel       基础模型名称（如 qwen2.5-7b）
     * @param fineTunedModel  微调后模型名称（训练完成后填充）
     * @param status          当前状态
     * @param datasetName     关联数据集名称
     * @param hyperparams     训练超参数（JSON）
     * @param loraConfig      LoRA 配置（JSON）
     * @param metrics         训练/评估指标（JSON）
     * @param errorMessage    错误信息（FAILED 状态时填充）
     * @param createdAt       创建时间
     * @param updatedAt       最后更新时间
     */
    public record FineTuneTask(
            String taskId,
            String taskName,
            String baseModel,
            String fineTunedModel,
            FineTuneTaskStatus status,
            String datasetName,
            String hyperparams,
            String loraConfig,
            String metrics,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public FineTuneTask {
            taskId = taskId == null ? UUID.randomUUID().toString() : taskId;
            status = status == null ? FineTuneTaskStatus.CREATED : status;
            fineTunedModel = fineTunedModel == null ? "" : fineTunedModel;
            datasetName = datasetName == null ? "" : datasetName;
            hyperparams = hyperparams == null ? "{}" : hyperparams;
            loraConfig = loraConfig == null ? "{}" : loraConfig;
            metrics = metrics == null ? "{}" : metrics;
            errorMessage = errorMessage == null ? "" : errorMessage;
            createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
            updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
        }

        /**
         * 判断任务是否处于终态（ARCHIVED 或 FAILED）。
         *
         * @return true 表示任务已完成生命周期
         */
        public boolean isTerminal() {
            return status == FineTuneTaskStatus.ARCHIVED || status == FineTuneTaskStatus.FAILED;
        }

        /**
         * 判断任务是否可部署（DEPLOYED 状态）。
         *
         * @return true 表示任务已部署
         */
        public boolean isDeployed() {
            return status == FineTuneTaskStatus.DEPLOYED;
        }

        /**
         * 将任务记录序列化为 JSON 字符串。
         *
         * @return JSON 字符串
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskId", taskId);
            map.put("taskName", taskName);
            map.put("baseModel", baseModel);
            map.put("fineTunedModel", fineTunedModel);
            map.put("status", status.name());
            map.put("datasetName", datasetName);
            map.put("hyperparams", hyperparams);
            map.put("loraConfig", loraConfig);
            map.put("metrics", metrics);
            map.put("errorMessage", errorMessage);
            map.put("createdAt", createdAt.toString());
            map.put("updatedAt", updatedAt.toString());
            return writeJson(map);
        }
    }

    /**
     * 训练超参数。
     *
     * @param epochs                   训练轮数
     * @param batchSize                批大小
     * @param learningRate             学习率
     * @param warmupRatio              预热比例
     * @param weightDecay              权重衰减
     * @param gradientAccumulationSteps 梯度累积步数
     * @param maxSeqLength             最大序列长度
     * @param gradientCheckpointing    是否使用梯度检查点
     */
    public record Hyperparams(
            int epochs,
            int batchSize,
            double learningRate,
            double warmupRatio,
            double weightDecay,
            int gradientAccumulationSteps,
            int maxSeqLength,
            boolean gradientCheckpointing
    ) {
        /**
         * 从默认配置构建超参数。
         */
        public static Hyperparams fromDefaults(FineTuneProperties.DefaultHyperparams defaults) {
            return new Hyperparams(
                    defaults.getEpochs(),
                    defaults.getBatchSize(),
                    defaults.getLearningRate(),
                    defaults.getWarmupRatio(),
                    defaults.getWeightDecay(),
                    defaults.getGradientAccumulationSteps(),
                    defaults.getMaxSeqLength(),
                    defaults.isGradientCheckpointing()
            );
        }

        /**
         * 序列化为 JSON 字符串。
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("epochs", epochs);
            map.put("batchSize", batchSize);
            map.put("learningRate", learningRate);
            map.put("warmupRatio", warmupRatio);
            map.put("weightDecay", weightDecay);
            map.put("gradientAccumulationSteps", gradientAccumulationSteps);
            map.put("maxSeqLength", maxSeqLength);
            map.put("gradientCheckpointing", gradientCheckpointing);
            return writeJson(map);
        }
    }

    /**
     * 训练/评估指标。
     *
     * @param trainLoss      最终训练损失
     * @param evalLoss       验证集损失
     * @param evalAccuracy   验证集准确率（0-1）
     * @param perplexity     困惑度
     * @param trainingTimeMinutes 训练耗时（分钟）
     * @param gpuHours       GPU 使用时长（小时）
     * @param evaluationScore 评估总分（来自 ModelEvaluationService）
     */
    public record TrainMetrics(
            double trainLoss,
            double evalLoss,
            double evalAccuracy,
            double perplexity,
            double trainingTimeMinutes,
            double gpuHours,
            double evaluationScore
    ) {
        /** 创建指标实例 */
        public static TrainMetrics of(double trainLoss, double evalLoss, double perplexity) {
            return new TrainMetrics(trainLoss, evalLoss, 0.0, perplexity, 0.0, 0.0, 0.0);
        }

        /**
         * 序列化为 JSON 字符串。
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("trainLoss", trainLoss);
            map.put("evalLoss", evalLoss);
            map.put("evalAccuracy", evalAccuracy);
            map.put("perplexity", perplexity);
            map.put("trainingTimeMinutes", trainingTimeMinutes);
            map.put("gpuHours", gpuHours);
            map.put("evaluationScore", evaluationScore);
            return writeJson(map);
        }

        /**
         * 判断指标是否达到部署标准。
         *
         * @return true 表示指标达标
         */
        public boolean isDeploymentReady() {
            return evalLoss > 0 && evalLoss < trainLoss * 1.5 && evaluationScore >= 70.0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 任务管理方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建微调任务。
     *
     * <p>任务创建后状态为 CREATED，使用默认超参数和 LoRA 配置。
     * 数据集会注册到数据集注册表中，并执行格式验证。
     *
     * @param baseModel 基础模型名称
     * @param taskName  任务名称
     * @param dataset   训练数据集
     * @return 创建的微调任务
     */
    public FineTuneTask createTask(String baseModel, String taskName, FineTuneDataset dataset) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("微调框架未启用，请在 contentops.fine-tune.enabled 中开启");
        }
        if (baseModel == null || baseModel.isBlank()) {
            throw new IllegalArgumentException("基础模型名称不能为空");
        }
        if (dataset == null) {
            throw new IllegalArgumentException("训练数据集不能为空");
        }

        // 验证数据集
        FineTuneDataset.ValidationResult validation = dataset.validate();
        if (!validation.valid()) {
            log.warn("[FineTuneManager] 数据集验证失败: taskName={}, errors={}", taskName, validation.errors());
            throw new IllegalArgumentException("数据集验证失败: " + String.join("; ", validation.errors()));
        }

        // 去重
        FineTuneDataset deduped = dataset.deduplicated();
        FineTuneDataset.DeduplicationResult dedupResult = dataset.checkDuplicates();
        if (dedupResult.duplicateCount() > 0) {
            log.info("[FineTuneManager] 数据集去重: original={}, unique={}, duplicates={}",
                    dedupResult.totalSamples(), dedupResult.uniqueSamples(), dedupResult.duplicateCount());
        }

        // 注册数据集
        datasetRegistry.put(deduped.name(), deduped);

        // 构建超参数和 LoRA 配置
        Hyperparams hyperparams = Hyperparams.fromDefaults(properties.getDefaultParams());
        String loraConfigJson = serializeLoraConfig(properties.getLora());

        FineTuneTask task = new FineTuneTask(
                UUID.randomUUID().toString(),
                taskName,
                baseModel,
                "",
                FineTuneTaskStatus.CREATED,
                deduped.name(),
                hyperparams.toJson(),
                loraConfigJson,
                "{}",
                "",
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        // 持久化到数据库
        persistTask(task);
        taskCache.put(task.taskId(), task);

        log.info("[FineTuneManager] 微调任务已创建: taskId={}, taskName={}, baseModel={}, dataset={}, samples={}",
                task.taskId(), taskName, baseModel, deduped.name(), deduped.sampleCount());

        return task;
    }

    /**
     * 推进任务状态。
     *
     * <p>校验状态流转合法性，非法流转抛出异常。状态变更后自动更新数据库记录。
     *
     * @param taskId    任务 ID
     * @param target    目标状态
     * @throws IllegalStateException 当前状态无法流转到目标状态时抛出
     */
    public FineTuneTask transitionTo(String taskId, FineTuneTaskStatus target) {
        FineTuneTask task = getTaskOrThrow(taskId);

        if (!task.status().canTransitionTo(target)) {
            throw new IllegalStateException(String.format(
                    "非法状态流转: %s → %s（taskId=%s）", task.status(), target, taskId));
        }

        FineTuneTask updated = new FineTuneTask(
                task.taskId(), task.taskName(), task.baseModel(), task.fineTunedModel(),
                target, task.datasetName(), task.hyperparams(), task.loraConfig(),
                task.metrics(), task.errorMessage(), task.createdAt(), LocalDateTime.now()
        );

        persistTask(updated);
        taskCache.put(taskId, updated);

        log.info("[FineTuneManager] 任务状态流转: taskId={}, {} → {}", taskId, task.status(), target);
        return updated;
    }

    /**
     * 将任务标记为失败并记录错误信息。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误信息
     * @return 更新后的任务
     */
    public FineTuneTask failTask(String taskId, String errorMessage) {
        FineTuneTask task = getTaskOrThrow(taskId);
        FineTuneTask failed = new FineTuneTask(
                task.taskId(), task.taskName(), task.baseModel(), task.fineTunedModel(),
                FineTuneTaskStatus.FAILED, task.datasetName(), task.hyperparams(), task.loraConfig(),
                task.metrics(), errorMessage, task.createdAt(), LocalDateTime.now()
        );

        persistTask(failed);
        taskCache.put(taskId, failed);

        log.error("[FineTuneManager] 任务失败: taskId={}, error={}", taskId, errorMessage);
        return failed;
    }

    /**
     * 记录训练/评估指标。
     *
     * @param taskId  任务 ID
     * @param metrics 训练指标
     * @return 更新后的任务
     */
    public FineTuneTask recordMetrics(String taskId, TrainMetrics metrics) {
        FineTuneTask task = getTaskOrThrow(taskId);
        FineTuneTask updated = new FineTuneTask(
                task.taskId(), task.taskName(), task.baseModel(), task.fineTunedModel(),
                task.status(), task.datasetName(), task.hyperparams(), task.loraConfig(),
                metrics.toJson(), task.errorMessage(), task.createdAt(), LocalDateTime.now()
        );

        persistTask(updated);
        taskCache.put(taskId, updated);

        log.info("[FineTuneManager] 指标已记录: taskId={}, trainLoss={}, evalLoss={}, perplexity={}",
                taskId, metrics.trainLoss(), metrics.evalLoss(), metrics.perplexity());
        return updated;
    }

    /**
     * 设置微调后模型名称（训练完成后调用）。
     *
     * @param taskId          任务 ID
     * @param fineTunedModel  微调后模型名称
     * @return 更新后的任务
     */
    public FineTuneTask setFineTunedModel(String taskId, String fineTunedModel) {
        FineTuneTask task = getTaskOrThrow(taskId);
        FineTuneTask updated = new FineTuneTask(
                task.taskId(), task.taskName(), task.baseModel(), fineTunedModel,
                task.status(), task.datasetName(), task.hyperparams(), task.loraConfig(),
                task.metrics(), task.errorMessage(), task.createdAt(), LocalDateTime.now()
        );

        persistTask(updated);
        taskCache.put(taskId, updated);

        log.info("[FineTuneManager] 微调模型已设置: taskId={}, fineTunedModel={}", taskId, fineTunedModel);
        return updated;
    }

    // ════════════════════════════════════════════════════════════════
    // 查询方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据 ID 查询任务。
     *
     * @param taskId 任务 ID
     * @return 任务 Optional
     */
    public Optional<FineTuneTask> getTask(String taskId) {
        FineTuneTask cached = taskCache.get(taskId);
        if (cached != null) {
            return Optional.of(cached);
        }
        // 降级：从数据库查询
        return loadTaskFromDb(taskId);
    }

    /**
     * 查询所有任务（按创建时间倒序）。
     *
     * @return 任务列表
     */
    public List<FineTuneTask> listTasks() {
        return new ArrayList<>(taskCache.values().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList());
    }

    /**
     * 按状态过滤任务。
     *
     * @param status 任务状态
     * @return 符合状态的任务列表
     */
    public List<FineTuneTask> listTasksByStatus(FineTuneTaskStatus status) {
        return taskCache.values().stream()
                .filter(t -> t.status() == status)
                .toList();
    }

    /**
     * 获取已注册的数据集。
     *
     * @param name 数据集名称
     * @return 数据集 Optional
     */
    public Optional<FineTuneDataset> getDataset(String name) {
        return Optional.ofNullable(datasetRegistry.get(name));
    }

    /**
     * 获取所有已注册的数据集名称。
     *
     * @return 数据集名称列表
     */
    public List<String> listDatasetNames() {
        return new ArrayList<>(datasetRegistry.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    // 编排流程方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动数据准备阶段。
     *
     * <p>将任务从 CREATED 推进到 DATA_PREPARING，执行数据集验证与去重，
     * 验证通过后自动推进到 TRAINING 状态。
     *
     * @param taskId 任务 ID
     * @return 更新后的任务
     */
    public FineTuneTask startDataPreparation(String taskId) {
        FineTuneTask task = transitionTo(taskId, FineTuneTaskStatus.DATA_PREPARING);

        // 获取数据集并验证
        FineTuneDataset dataset = getDataset(task.datasetName())
                .orElseThrow(() -> new IllegalStateException("数据集不存在: " + task.datasetName()));

        FineTuneDataset.ValidationResult validation = dataset.validate();
        if (!validation.valid()) {
            return failTask(taskId, "数据集验证失败: " + String.join("; ", validation.errors()));
        }

        log.info("[FineTuneManager] 数据准备完成: taskId={}, samples={}", taskId, dataset.sampleCount());
        return transitionTo(taskId, FineTuneTaskStatus.TRAINING);
    }

    /**
     * 完成训练阶段并进入评估。
     *
     * <p>由外部训练集群在训练完成后回调，记录训练指标并推进到 EVALUATING 状态。
     *
     * @param taskId        任务 ID
     * @param fineTunedModel 微调后模型名称
     * @param metrics       训练指标
     * @return 更新后的任务
     */
    public FineTuneTask completeTraining(String taskId, String fineTunedModel, TrainMetrics metrics) {
        FineTuneTask task = setFineTunedModel(taskId, fineTunedModel);
        task = recordMetrics(taskId, metrics);
        return transitionTo(taskId, FineTuneTaskStatus.EVALUATING);
    }

    /**
     * 完成评估并决定是否部署。
     *
     * <p>评估指标达标则推进到 DEPLOYED，否则标记为 FAILED。
     *
     * @param taskId          任务 ID
     * @param evaluationScore 评估总分
     * @return 更新后的任务
     */
    public FineTuneTask completeEvaluation(String taskId, double evaluationScore) {
        FineTuneTask task = getTaskOrThrow(taskId);

        // 更新指标中的评估分数
        TrainMetrics existing = deserializeMetrics(task.metrics());
        TrainMetrics updatedMetrics = new TrainMetrics(
                existing.trainLoss(), existing.evalLoss(), existing.evalAccuracy(),
                existing.perplexity(), existing.trainingTimeMinutes(),
                existing.gpuHours(), evaluationScore
        );
        recordMetrics(taskId, updatedMetrics);

        // 判断是否达标
        if (updatedMetrics.isDeploymentReady()) {
            log.info("[FineTuneManager] 评估通过，准备部署: taskId={}, score={}", taskId, evaluationScore);
            return transitionTo(taskId, FineTuneTaskStatus.DEPLOYED);
        } else {
            return failTask(taskId, String.format("评估未达标: score=%.2f（阈值 70.0）", evaluationScore));
        }
    }

    /**
     * 归档任务。
     *
     * @param taskId 任务 ID
     * @return 更新后的任务
     */
    public FineTuneTask archiveTask(String taskId) {
        return transitionTo(taskId, FineTuneTaskStatus.ARCHIVED);
    }

    // ════════════════════════════════════════════════════════════════
    // 数据集导出方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 导出数据集为 JSONL 字符串。
     *
     * @param datasetName 数据集名称
     * @return JSONL 字符串
     */
    public String exportDatasetAsJsonl(String datasetName) {
        FineTuneDataset dataset = getDataset(datasetName)
                .orElseThrow(() -> new IllegalArgumentException("数据集不存在: " + datasetName));
        return dataset.toJsonl();
    }

    // ════════════════════════════════════════════════════════════════
    // 内部工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取任务，不存在时抛出异常。
     */
    private FineTuneTask getTaskOrThrow(String taskId) {
        return getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }

    /**
     * 序列化 LoRA 配置为 JSON。
     */
    private String serializeLoraConfig(FineTuneProperties.LoraConfig lora) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rank", lora.getRank());
        map.put("alpha", lora.getAlpha());
        map.put("dropout", lora.getDropout());
        map.put("targetModules", lora.getTargetModules());
        map.put("bias", lora.getBias());
        map.put("taskType", lora.getTaskType());
        return writeJson(map);
    }

    /**
     * 反序列化指标 JSON。
     */
    private TrainMetrics deserializeMetrics(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            return new TrainMetrics(
                    toDouble(map.get("trainLoss")),
                    toDouble(map.get("evalLoss")),
                    toDouble(map.get("evalAccuracy")),
                    toDouble(map.get("perplexity")),
                    toDouble(map.get("trainingTimeMinutes")),
                    toDouble(map.get("gpuHours")),
                    toDouble(map.get("evaluationScore"))
            );
        } catch (Exception e) {
            log.warn("[FineTuneManager] 指标反序列化失败，使用默认值: {}", e.getMessage());
            return new TrainMetrics(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 安全转换为 double。
     */
    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    /**
     * 持久化任务到数据库。
     *
     * <p>使用 upsert 语义（INSERT ON CONFLICT），表结构假设为：
     * <pre>
     * CREATE TABLE IF NOT EXISTS fine_tune_tasks (
     *     task_id VARCHAR(64) PRIMARY KEY,
     *     task_name VARCHAR(255),
     *     base_model VARCHAR(255),
     *     fine_tuned_model VARCHAR(255),
     *     status VARCHAR(32),
     *     dataset_name VARCHAR(255),
     *     hyperparams TEXT,
     *     lora_config TEXT,
     *     metrics TEXT,
     *     error_message TEXT,
     *     created_at TIMESTAMP,
     *     updated_at TIMESTAMP
     * );
     * </pre>
     *
     * <p>数据库不可用时降级为仅内存存储，不影响编排流程。
     */
    private void persistTask(FineTuneTask task) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO fine_tune_tasks (task_id, task_name, base_model, fine_tuned_model, " +
                            "status, dataset_name, hyperparams, lora_config, metrics, error_message, " +
                            "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (task_id) DO UPDATE SET task_name=EXCLUDED.task_name, " +
                            "base_model=EXCLUDED.base_model, fine_tuned_model=EXCLUDED.fine_tuned_model, " +
                            "status=EXCLUDED.status, dataset_name=EXCLUDED.dataset_name, " +
                            "hyperparams=EXCLUDED.hyperparams, lora_config=EXCLUDED.lora_config, " +
                            "metrics=EXCLUDED.metrics, error_message=EXCLUDED.error_message, " +
                            "updated_at=EXCLUDED.updated_at",
                    task.taskId(), task.taskName(), task.baseModel(), task.fineTunedModel(),
                    task.status().name(), task.datasetName(), task.hyperparams(), task.loraConfig(),
                    task.metrics(), task.errorMessage(), task.createdAt(), task.updatedAt()
            );
        } catch (Exception e) {
            // 降级：数据库不可用时仅使用内存缓存
            log.warn("[FineTuneManager] 数据库持久化失败，降级为内存存储: taskId={}, error={}",
                    task.taskId(), e.getMessage());
        }
    }

    /**
     * 从数据库加载任务（降级查询）。
     */
    private Optional<FineTuneTask> loadTaskFromDb(String taskId) {
        try {
            List<FineTuneTask> tasks = jdbcTemplate.query(
                    "SELECT * FROM fine_tune_tasks WHERE task_id = ?",
                    (rs, rowNum) -> new FineTuneTask(
                            rs.getString("task_id"),
                            rs.getString("task_name"),
                            rs.getString("base_model"),
                            rs.getString("fine_tuned_model"),
                            FineTuneTaskStatus.valueOf(rs.getString("status")),
                            rs.getString("dataset_name"),
                            rs.getString("hyperparams"),
                            rs.getString("lora_config"),
                            rs.getString("metrics"),
                            rs.getString("error_message"),
                            rs.getTimestamp("created_at") != null
                                    ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                            rs.getTimestamp("updated_at") != null
                                    ? rs.getTimestamp("updated_at").toLocalDateTime() : null
                    ),
                    taskId
            );
            if (!tasks.isEmpty()) {
                FineTuneTask task = tasks.get(0);
                taskCache.put(taskId, task);
                return Optional.of(task);
            }
        } catch (Exception e) {
            log.warn("[FineTuneManager] 数据库查询失败: taskId={}, error={}", taskId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    private static String writeJson(Map<String, Object> map) {
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }

    /**
     * 创建并配置 ObjectMapper 实例。
     */
    private static ObjectMapper createObjectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
