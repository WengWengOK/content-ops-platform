package com.contentops.common.finetune;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型部署管理器（编排层，不执行实际推理请求）。
 *
 * <p>管理微调模型的部署生命周期，支持多种部署模式、版本管理、灰度发布和健康检查。
 * 本管理器负责路由决策和元数据管理，实际推理请求由下游 API 网关或本地推理引擎处理。
 *
 * <h3>三种部署模式</h3>
 * <ul>
 *   <li><b>API 模式</b>（{@link DeploymentMode#API}）：调用 OpenAI / 通义千问等远程 API，
 *       无需本地 GPU，延迟低但依赖网络</li>
 *   <li><b>本地部署模式</b>（{@link DeploymentMode#LOCAL}）：通过 Ollama / vLLM 接口调用本地模型，
 *       数据不出域但需要 GPU 资源</li>
 *   <li><b>混合模式</b>（{@link DeploymentMode#HYBRID}）：优先使用本地推理，本地不可用时降级到 API，
 *       兼顾延迟与可用性</li>
 * </ul>
 *
 * <h3>版本管理</h3>
 * <ul>
 *   <li>支持多版本共存，每个版本有独立的流量比例</li>
 *   <li>灰度发布：新版本从小流量比例开始，逐步提升</li>
 *   <li>A/B 测试：与 {@link ModelAbTest} 配合，进行统计显著性检验</li>
 * </ul>
 *
 * <h3>健康检查与自动切换</h3>
 * <p>定期检查各部署端点健康状态，异常时自动切换到备用模式。
 * 混合模式下，本地推理超时后自动降级到 API 模式。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 部署微调模型
 * ModelVersion version = manager.deploy("qwen2.5-7b-finetuned-v1",
 *     DeploymentMode.HYBRID, 100);
 *
 * // 灰度发布新版本
 * manager.startCanaryRelease("qwen2.5-7b-finetuned-v2", 10);
 *
 * // 提升灰度比例
 * manager.updateTrafficRatio("qwen2.5-7b-finetuned-v2", 50);
 *
 * // 健康检查
 * HealthStatus health = manager.checkHealth("qwen2.5-7b-finetuned-v1");
 * }</pre>
 *
 * @see FineTuneProperties
 * @see ModelFineTuneManager
 * @see ModelAbTest
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentManager {

    private final FineTuneProperties properties;

    /** 已部署模型版本注册表（按模型 ID 索引） */
    private final Map<String, ModelVersion> versionRegistry = new ConcurrentHashMap<>();

    /** 灰度发布配置（按基础模型名称索引） */
    private final Map<String, CanaryRelease> canaryReleases = new ConcurrentHashMap<>();

    /** 健康状态缓存 */
    private final Map<String, HealthStatus> healthStatusCache = new ConcurrentHashMap<>();

    /** 请求计数器（按模型 ID 索引，用于流量统计） */
    private final Map<String, AtomicLong> requestCounters = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════
    // 部署模式枚举
    // ════════════════════════════════════════════════════════════════

    /**
     * 模型部署模式。
     */
    public enum DeploymentMode {
        /** API 模式：调用远程 API（OpenAI / 通义千问等） */
        API,
        /** 本地部署模式：通过 Ollama / vLLM 接口调用本地模型 */
        LOCAL,
        /** 混合模式：优先本地，降级到 API */
        HYBRID
    }

    /**
     * 健康状态枚举。
     */
    public enum HealthState {
        /** 健康 */
        HEALTHY,
        /** 降级（本地不可用，已切换到 API） */
        DEGRADED,
        /** 不可用 */
        UNHEALTHY,
        /** 未知（尚未检查） */
        UNKNOWN
    }

    // ════════════════════════════════════════════════════════════════
    // 模型版本记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 模型版本记录。
     *
     * @param modelId      模型 ID（微调后模型名称）
     * @param baseModel    基础模型名称
     * @param version      版本号
     * @param mode         部署模式
     * @param trafficPercent 流量比例（0-100）
     * @param active       是否处于活跃状态
     * @param endpoint     推理端点地址
     * @param deployedAt   部署时间
     * @param lastHealthCheck 最后健康检查时间
     */
    public record ModelVersion(
            String modelId,
            String baseModel,
            String version,
            DeploymentMode mode,
            int trafficPercent,
            boolean active,
            String endpoint,
            LocalDateTime deployedAt,
            LocalDateTime lastHealthCheck
    ) {
        public ModelVersion {
            modelId = modelId == null ? "" : modelId;
            baseModel = baseModel == null ? "" : baseModel;
            version = version == null ? "v1" : version;
            trafficPercent = Math.max(0, Math.min(100, trafficPercent));
            endpoint = endpoint == null ? "" : endpoint;
            deployedAt = deployedAt == null ? LocalDateTime.now() : deployedAt;
        }
    }

    /**
     * 健康状态记录。
     *
     * @param modelId        模型 ID
     * @param state          健康状态
     * @param localHealthy   本地推理是否健康
     * @param apiHealthy     API 推理是否健康
     * @param latencyMs      最近请求延迟（毫秒）
     * @param errorMessage   错误信息
     * @param checkedAt      检查时间
     */
    public record HealthStatus(
            String modelId,
            HealthState state,
            boolean localHealthy,
            boolean apiHealthy,
            long latencyMs,
            String errorMessage,
            LocalDateTime checkedAt
    ) {
        public HealthStatus {
            modelId = modelId == null ? "" : modelId;
            state = state == null ? HealthState.UNKNOWN : state;
            errorMessage = errorMessage == null ? "" : errorMessage;
            checkedAt = checkedAt == null ? LocalDateTime.now() : checkedAt;
        }

        /** 创建健康状态 */
        public static HealthStatus healthy(String modelId, long latencyMs) {
            return new HealthStatus(modelId, HealthState.HEALTHY, true, true,
                    latencyMs, "", LocalDateTime.now());
        }

        /** 创建降级状态 */
        public static HealthStatus degraded(String modelId, String reason) {
            return new HealthStatus(modelId, HealthState.DEGRADED, false, true,
                    0, reason, LocalDateTime.now());
        }

        /** 创建不可用状态 */
        public static HealthStatus unhealthy(String modelId, String reason) {
            return new HealthStatus(modelId, HealthState.UNHEALTHY, false, false,
                    0, reason, LocalDateTime.now());
        }
    }

    /**
     * 灰度发布配置。
     *
     * @param baseModel      基础模型名称
     * @param stableVersion  稳定版本模型 ID
     * @param canaryVersion  灰度版本模型 ID
     * @param canaryPercent  灰度流量比例（0-100）
     * @param startedAt      灰度开始时间
     * @param promoted       是否已全量发布
     */
    public record CanaryRelease(
            String baseModel,
            String stableVersion,
            String canaryVersion,
            int canaryPercent,
            LocalDateTime startedAt,
            boolean promoted
    ) {
        public CanaryRelease {
            canaryPercent = Math.max(0, Math.min(100, canaryPercent));
        }
    }

    /**
     * 部署路由结果。
     *
     * @param modelId   被路由到的模型 ID
     * @param mode      实际使用的部署模式
     * @param endpoint  推理端点地址
     * @param degraded  是否为降级路由
     * @param reason    路由原因说明
     */
    public record DeploymentRoute(
            String modelId,
            DeploymentMode mode,
            String endpoint,
            boolean degraded,
            String reason
    ) {
    }

    // ════════════════════════════════════════════════════════════════
    // 部署管理方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 部署模型版本。
     *
     * <p>注册模型版本并设置初始流量比例。部署模式从配置读取默认值，
     * 也可通过参数显式指定。
     *
     * @param modelId         微调后模型 ID
     * @param baseModel       基础模型名称
     * @param mode            部署模式（null 时使用配置默认值）
     * @param trafficPercent  初始流量比例（0-100）
     * @return 部署的模型版本
     */
    public ModelVersion deploy(String modelId, String baseModel, DeploymentMode mode, int trafficPercent) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }

        DeploymentMode deployMode = mode != null ? mode : parseDefaultMode();
        String endpoint = resolveEndpoint(deployMode);

        ModelVersion version = new ModelVersion(
                modelId, baseModel, deriveVersion(modelId),
                deployMode, trafficPercent, true,
                endpoint, LocalDateTime.now(), null
        );

        versionRegistry.put(modelId, version);
        requestCounters.put(modelId, new AtomicLong(0));

        // 初始化健康状态为 UNKNOWN
        healthStatusCache.put(modelId, new HealthStatus(
                modelId, HealthState.UNKNOWN, false, false, 0, "", LocalDateTime.now()
        ));

        log.info("[ModelDeploy] 模型已部署: modelId={}, baseModel={}, mode={}, traffic={}%, endpoint={}",
                modelId, baseModel, deployMode, trafficPercent, endpoint);

        return version;
    }

    /**
     * 部署模型版本（便捷方法，使用默认部署模式）。
     *
     * @param modelId        微调后模型 ID
     * @param trafficPercent 初始流量比例
     * @return 部署的模型版本
     */
    public ModelVersion deploy(String modelId, int trafficPercent) {
        return deploy(modelId, "", null, trafficPercent);
    }

    /**
     * 下线模型版本。
     *
     * @param modelId 模型 ID
     */
    public void undeploy(String modelId) {
        ModelVersion removed = versionRegistry.remove(modelId);
        healthStatusCache.remove(modelId);
        requestCounters.remove(modelId);
        if (removed != null) {
            log.info("[ModelDeploy] 模型已下线: modelId={}", modelId);
        }
    }

    /**
     * 更新模型流量比例。
     *
     * @param modelId         模型 ID
     * @param trafficPercent  新的流量比例（0-100）
     * @return 更新后的模型版本
     */
    public ModelVersion updateTrafficRatio(String modelId, int trafficPercent) {
        ModelVersion version = getVersionOrThrow(modelId);
        ModelVersion updated = new ModelVersion(
                version.modelId(), version.baseModel(), version.version(),
                version.mode(), trafficPercent, version.active(),
                version.endpoint(), version.deployedAt(), version.lastHealthCheck()
        );
        versionRegistry.put(modelId, updated);
        log.info("[ModelDeploy] 流量比例更新: modelId={}, traffic={}%", modelId, trafficPercent);
        return updated;
    }

    // ════════════════════════════════════════════════════════════════
    // 灰度发布方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动灰度发布。
     *
     * <p>将新版本以小流量比例部署，与稳定版本并行运行。
     * 灰度比例从配置的 {@code defaultCanaryPercentage} 读取（默认 10%）。
     *
     * @param baseModel       基础模型名称
     * @param stableVersion   稳定版本模型 ID
     * @param canaryVersion   灰度版本模型 ID
     * @return 灰度发布配置
     */
    public CanaryRelease startCanaryRelease(String baseModel, String stableVersion, String canaryVersion) {
        int initialPercent = properties.getDeployment().getDefaultCanaryPercentage();

        // 调整流量比例
        updateTrafficRatio(stableVersion, 100 - initialPercent);
        updateTrafficRatio(canaryVersion, initialPercent);

        CanaryRelease release = new CanaryRelease(
                baseModel, stableVersion, canaryVersion,
                initialPercent, LocalDateTime.now(), false
        );
        canaryReleases.put(baseModel, release);

        log.info("[ModelDeploy] 灰度发布已启动: baseModel={}, stable={}, canary={}, canaryPercent={}%",
                baseModel, stableVersion, canaryVersion, initialPercent);

        return release;
    }

    /**
     * 便捷方法：启动灰度发布（自动推断基础模型名称）。
     *
     * @param canaryVersion 灰度版本模型 ID
     * @param canaryPercent 灰度流量比例
     * @return 灰度发布配置
     */
    public CanaryRelease startCanaryRelease(String canaryVersion, int canaryPercent) {
        String baseModel = deriveBaseModel(canaryVersion);
        // 寻找同基础模型的稳定版本
        String stableVersion = versionRegistry.values().stream()
                .filter(v -> v.baseModel().equals(baseModel) && v.active() && !v.modelId().equals(canaryVersion))
                .map(ModelVersion::modelId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到稳定版本用于灰度发布"));

        CanaryRelease release = new CanaryRelease(
                baseModel, stableVersion, canaryVersion,
                canaryPercent, LocalDateTime.now(), false
        );
        canaryReleases.put(baseModel, release);

        updateTrafficRatio(stableVersion, 100 - canaryPercent);
        updateTrafficRatio(canaryVersion, canaryPercent);

        log.info("[ModelDeploy] 灰度发布已启动: stable={}, canary={}, percent={}%",
                stableVersion, canaryVersion, canaryPercent);
        return release;
    }

    /**
     * 提升灰度比例。
     *
     * @param baseModel    基础模型名称
     * @param newPercent   新的灰度流量比例
     * @return 更新后的灰度发布配置
     */
    public CanaryRelease promoteCanary(String baseModel, int newPercent) {
        CanaryRelease release = canaryReleases.get(baseModel);
        if (release == null) {
            throw new IllegalStateException("未找到灰度发布配置: " + baseModel);
        }

        int clamped = Math.max(0, Math.min(100, newPercent));
        updateTrafficRatio(release.stableVersion(), 100 - clamped);
        updateTrafficRatio(release.canaryVersion(), clamped);

        CanaryRelease updated = new CanaryRelease(
                baseModel, release.stableVersion(), release.canaryVersion(),
                clamped, release.startedAt(), false
        );
        canaryReleases.put(baseModel, updated);

        log.info("[ModelDeploy] 灰度比例提升: baseModel={}, canaryPercent={}%", baseModel, clamped);
        return updated;
    }

    /**
     * 完成灰度发布，将灰度版本全量上线。
     *
     * @param baseModel 基础模型名称
     * @return 更新后的灰度发布配置
     */
    public CanaryRelease completeCanaryRelease(String baseModel) {
        CanaryRelease release = canaryReleases.get(baseModel);
        if (release == null) {
            throw new IllegalStateException("未找到灰度发布配置: " + baseModel);
        }

        updateTrafficRatio(release.canaryVersion(), 100);
        updateTrafficRatio(release.stableVersion(), 0);

        CanaryRelease completed = new CanaryRelease(
                baseModel, release.stableVersion(), release.canaryVersion(),
                100, release.startedAt(), true
        );
        canaryReleases.put(baseModel, completed);

        log.info("[ModelDeploy] 灰度发布已完成，灰度版本已全量上线: baseModel={}, canary={}",
                baseModel, release.canaryVersion());
        return completed;
    }

    /**
     * 回滚灰度发布，恢复稳定版本。
     *
     * @param baseModel 基础模型名称
     * @return 更新后的灰度发布配置
     */
    public CanaryRelease rollbackCanary(String baseModel) {
        CanaryRelease release = canaryReleases.get(baseModel);
        if (release == null) {
            throw new IllegalStateException("未找到灰度发布配置: " + baseModel);
        }

        updateTrafficRatio(release.stableVersion(), 100);
        updateTrafficRatio(release.canaryVersion(), 0);

        log.warn("[ModelDeploy] 灰度发布已回滚，恢复稳定版本: baseModel={}, stable={}",
                baseModel, release.stableVersion());

        return release;
    }

    // ════════════════════════════════════════════════════════════════
    // 路由与降级方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据部署模式路由推理请求。
     *
     * <p>路由策略：
     * <ul>
     *   <li>API 模式：直接路由到 API 端点</li>
     *   <li>LOCAL 模式：直接路由到本地端点</li>
     *   <li>HYBRID 模式：优先本地，本地不健康时降级到 API</li>
     * </ul>
     *
     * @param modelId 模型 ID
     * @return 部署路由结果
     */
    public DeploymentRoute route(String modelId) {
        ModelVersion version = getVersionOrThrow(modelId);
        requestCounters.computeIfAbsent(modelId, k -> new AtomicLong(0)).incrementAndGet();

        HealthStatus health = healthStatusCache.getOrDefault(modelId,
                new HealthStatus(modelId, HealthState.UNKNOWN, false, false, 0, "", LocalDateTime.now()));

        return switch (version.mode()) {
            case API -> new DeploymentRoute(
                    modelId, DeploymentMode.API,
                    resolveEndpoint(DeploymentMode.API),
                    false, "API 模式直接路由"
            );
            case LOCAL -> {
                if (health.localHealthy() || health.state() == HealthState.UNKNOWN) {
                    yield new DeploymentRoute(
                            modelId, DeploymentMode.LOCAL,
                            resolveEndpoint(DeploymentMode.LOCAL),
                            false, "本地模式路由"
                    );
                } else {
                    // 本地不可用时降级到 API
                    log.warn("[ModelDeploy] 本地模型不可用，降级到 API: modelId={}", modelId);
                    yield new DeploymentRoute(
                            modelId, DeploymentMode.API,
                            resolveEndpoint(DeploymentMode.API),
                            true, "本地不可用，降级到 API"
                    );
                }
            }
            case HYBRID -> {
                if (health.localHealthy() || health.state() == HealthState.UNKNOWN) {
                    yield new DeploymentRoute(
                            modelId, DeploymentMode.LOCAL,
                            resolveEndpoint(DeploymentMode.LOCAL),
                            false, "混合模式优先本地"
                    );
                } else if (health.apiHealthy()) {
                    log.warn("[ModelDeploy] 混合模式本地不可用，降级到 API: modelId={}", modelId);
                    yield new DeploymentRoute(
                            modelId, DeploymentMode.API,
                            resolveEndpoint(DeploymentMode.API),
                            true, "混合模式降级到 API"
                    );
                } else {
                    // 全部不可用，仍尝试本地作为最后手段
                    yield new DeploymentRoute(
                            modelId, DeploymentMode.LOCAL,
                            resolveEndpoint(DeploymentMode.LOCAL),
                            true, "API 与本地均不可用，尝试本地恢复"
                    );
                }
            }
        };
    }

    // ════════════════════════════════════════════════════════════════
    // 健康检查方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查模型健康状态。
     *
     * <p>模拟健康检查逻辑（实际部署中应发送真实探测请求）：
     * <ul>
     *   <li>API 模式：检查 API 端点连通性</li>
     *   <li>LOCAL 模式：检查 Ollama / vLLM 服务状态</li>
     *   <li>HYBRID 模式：同时检查本地和 API</li>
     * </ul>
     *
     * @param modelId 模型 ID
     * @return 健康状态
     */
    public HealthStatus checkHealth(String modelId) {
        ModelVersion version = getVersionOrThrow(modelId);
        long startTime = System.currentTimeMillis();

        // 模拟健康检查（实际应发送 HTTP 探测请求）
        boolean localOk = simulateLocalHealthCheck();
        boolean apiOk = simulateApiHealthCheck();
        long latency = System.currentTimeMillis() - startTime;

        HealthStatus status;
        switch (version.mode()) {
            case API -> status = apiOk
                    ? HealthStatus.healthy(modelId, latency)
                    : HealthStatus.unhealthy(modelId, "API 端点不可达");
            case LOCAL -> status = localOk
                    ? HealthStatus.healthy(modelId, latency)
                    : HealthStatus.unhealthy(modelId, "本地推理引擎不可达");
            case HYBRID -> {
                if (localOk && apiOk) {
                    status = HealthStatus.healthy(modelId, latency);
                } else if (apiOk) {
                    status = HealthStatus.degraded(modelId, "本地不可用，已降级到 API");
                } else if (localOk) {
                    status = HealthStatus.degraded(modelId, "API 不可用，仅使用本地");
                } else {
                    status = HealthStatus.unhealthy(modelId, "本地与 API 均不可用");
                }
            }
            default -> status = HealthStatus.unhealthy(modelId, "未知部署模式");
        }

        healthStatusCache.put(modelId, status);

        // 更新版本的最后检查时间
        ModelVersion updated = new ModelVersion(
                version.modelId(), version.baseModel(), version.version(),
                version.mode(), version.trafficPercent(), version.active(),
                version.endpoint(), version.deployedAt(), LocalDateTime.now()
        );
        versionRegistry.put(modelId, updated);

        log.debug("[ModelDeploy] 健康检查: modelId={}, state={}, latency={}ms", modelId, status.state(), latency);
        return status;
    }

    /**
     * 批量健康检查所有已部署模型。
     *
     * @return 健康状态列表
     */
    public List<HealthStatus> checkAllHealth() {
        List<HealthStatus> results = new ArrayList<>();
        for (String modelId : versionRegistry.keySet()) {
            results.add(checkHealth(modelId));
        }
        return results;
    }

    /**
     * 自动切换：对不健康的模型执行降级或恢复。
     *
     * <p>遍历所有已部署模型，对不健康的模型切换到备用模式。
     * 混合模式下自动在本地和 API 之间切换。
     */
    public void autoSwitch() {
        for (Map.Entry<String, HealthStatus> entry : healthStatusCache.entrySet()) {
            String modelId = entry.getKey();
            HealthStatus health = entry.getValue();
            ModelVersion version = versionRegistry.get(modelId);

            if (version == null || !version.active()) {
                continue;
            }

            if (health.state() == HealthState.UNHEALTHY && version.mode() == DeploymentMode.LOCAL) {
                // 本地模式不可用时自动切换到混合模式
                log.warn("[ModelDeploy] 自动切换: modelId={} LOCAL → HYBRID（本地不可用）", modelId);
                ModelVersion switched = new ModelVersion(
                        version.modelId(), version.baseModel(), version.version(),
                        DeploymentMode.HYBRID, version.trafficPercent(), version.active(),
                        resolveEndpoint(DeploymentMode.HYBRID), version.deployedAt(), version.lastHealthCheck()
                );
                versionRegistry.put(modelId, switched);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 查询方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 查询模型版本。
     *
     * @param modelId 模型 ID
     * @return 模型版本 Optional
     */
    public Optional<ModelVersion> getVersion(String modelId) {
        return Optional.ofNullable(versionRegistry.get(modelId));
    }

    /**
     * 列出所有已部署模型版本。
     *
     * @return 模型版本列表
     */
    public List<ModelVersion> listVersions() {
        return new ArrayList<>(versionRegistry.values().stream()
                .sorted(Comparator.comparing(ModelVersion::deployedAt).reversed())
                .toList());
    }

    /**
     * 按基础模型列出版本。
     *
     * @param baseModel 基础模型名称
     * @return 该基础模型的所有版本
     */
    public List<ModelVersion> listVersionsByBaseModel(String baseModel) {
        return versionRegistry.values().stream()
                .filter(v -> v.baseModel().equals(baseModel))
                .sorted(Comparator.comparing(ModelVersion::deployedAt).reversed())
                .toList();
    }

    /**
     * 获取灰度发布配置。
     *
     * @param baseModel 基础模型名称
     * @return 灰度发布配置 Optional
     */
    public Optional<CanaryRelease> getCanaryRelease(String baseModel) {
        return Optional.ofNullable(canaryReleases.get(baseModel));
    }

    /**
     * 获取模型请求计数。
     *
     * @param modelId 模型 ID
     * @return 请求总数
     */
    public long getRequestCount(String modelId) {
        AtomicLong counter = requestCounters.get(modelId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取健康状态缓存。
     *
     * @param modelId 模型 ID
     * @return 健康状态 Optional
     */
    public Optional<HealthStatus> getHealthStatus(String modelId) {
        return Optional.ofNullable(healthStatusCache.get(modelId));
    }

    // ════════════════════════════════════════════════════════════════
    // 内部工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取模型版本，不存在时抛出异常。
     */
    private ModelVersion getVersionOrThrow(String modelId) {
        return getVersion(modelId)
                .orElseThrow(() -> new IllegalArgumentException("模型版本不存在: " + modelId));
    }

    /**
     * 解析默认部署模式。
     */
    private DeploymentMode parseDefaultMode() {
        String mode = properties.getDeployment().getDefaultMode();
        try {
            return DeploymentMode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[ModelDeploy] 未知部署模式: {}，降级为 HYBRID", mode);
            return DeploymentMode.HYBRID;
        }
    }

    /**
     * 根据部署模式解析推理端点地址。
     */
    private String resolveEndpoint(DeploymentMode mode) {
        return switch (mode) {
            case API -> properties.getDeployment().getApi().getBaseUrl();
            case LOCAL -> {
                FineTuneProperties.LocalConfig local = properties.getDeployment().getLocal();
                yield "ollama".equalsIgnoreCase(local.getPreferredEngine())
                        ? local.getOllamaUrl()
                        : local.getVllmUrl();
            }
            case HYBRID -> properties.getDeployment().getLocal().getOllamaUrl();
        };
    }

    /**
     * 从模型 ID 推导版本号。
     */
    private String deriveVersion(String modelId) {
        if (modelId.contains("-v")) {
            int idx = modelId.lastIndexOf("-v");
            if (idx + 2 < modelId.length()) {
                return modelId.substring(idx + 1);
            }
        }
        return "v1";
    }

    /**
     * 从模型 ID 推导基础模型名称。
     */
    private String deriveBaseModel(String modelId) {
        if (modelId.contains("-finetuned")) {
            return modelId.substring(0, modelId.indexOf("-finetuned"));
        }
        if (modelId.contains("-v")) {
            return modelId.substring(0, modelId.lastIndexOf("-v"));
        }
        return modelId;
    }

    /**
     * 模拟本地健康检查（实际应发送 HTTP 请求到 Ollama/vLLM）。
     */
    private boolean simulateLocalHealthCheck() {
        // 编排层不执行实际网络请求，返回 true 表示假设本地可用
        // 实际部署时应替换为真实的健康探测逻辑
        return true;
    }

    /**
     * 模拟 API 健康检查。
     */
    private boolean simulateApiHealthCheck() {
        // 编排层不执行实际网络请求，返回 true 表示假设 API 可用
        return true;
    }
}
