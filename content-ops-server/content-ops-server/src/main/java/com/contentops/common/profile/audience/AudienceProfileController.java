package com.contentops.common.profile.audience;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 受众画像 REST API 控制器（P1 用户画像扩展系统）。
 *
 * <p>提供受众画像与内容画像的构建、查询、指标导入与摘要生成端点，
 * 统一以 {@link AgentResponse} 包装响应，遵循平台标准返回格式。
 *
 * <h3>端点一览</h3>
 * <ul>
 *   <li>{@code POST /api/v1/audience-profiles/{accountId}} —— 构建受众画像（从平台 API 拉取）</li>
 *   <li>{@code GET  /api/v1/audience-profiles/{accountId}} —— 获取受众画像</li>
 *   <li>{@code POST /api/v1/audience-profiles/{accountId}/from-metrics} —— 从指标文本构建画像</li>
 *   <li>{@code GET  /api/v1/audience-profiles/{accountId}/summary} —— 获取画像摘要（人类可读）</li>
 * </ul>
 *
 * @see AudienceProfileService
 * @see ProfileEnricher
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audience-profiles")
@RequiredArgsConstructor
@Tag(name = "受众画像", description = "P1 用户画像扩展系统 —— 多维结构化受众与内容画像管理")
public class AudienceProfileController {

    /** AgentResponse 中的阶段标识。 */
    private static final String STAGE = "audience-profile";

    private final AudienceProfileService profileService;
    private final ProfileEnricher profileEnricher;

    /**
     * 构建受众画像（从平台 API 拉取粉丝数据）。
     *
     * @param accountId 账号 ID
     * @param platform  平台标识（可选，默认走配置）
     * @return 201 + 构建的受众画像
     */
    @Operation(summary = "构建受众画像", description = "从平台 API 拉取粉丝数据构建多维结构化受众画像")
    @PostMapping("/{accountId}")
    public ResponseEntity<AgentResponse<AudienceProfile>> buildProfile(
            @PathVariable String accountId,
            @RequestParam(value = "platform", required = false) String platform) {
        log.info("构建受众画像: accountId={}, platform={}", accountId, platform);
        AudienceProfile profile = platform != null && !platform.isBlank()
                ? profileService.buildProfile(accountId, platform)
                : profileService.buildProfile(accountId);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hasData", profile.hasData());
        metadata.put("platform", platform != null ? platform : "default");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentResponse.success(STAGE, profile, metadata));
    }

    /**
     * 获取受众画像。
     *
     * @param accountId 账号 ID
     * @return 200 + 画像；不存在返回 404
     */
    @Operation(summary = "获取受众画像", description = "根据 accountId 获取受众画像（命中缓存优先）")
    @GetMapping("/{accountId}")
    public ResponseEntity<AgentResponse<AudienceProfile>> getProfile(@PathVariable String accountId) {
        AudienceProfile profile = profileService.getProfile(accountId);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure(STAGE, "受众画像不存在: " + accountId));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hasData", profile.hasData());
        return ResponseEntity.ok(AgentResponse.success(STAGE, profile, metadata));
    }

    /**
     * 从指标文本构建画像。
     *
     * <p>当无法直接调用平台 API 时，可传入平台导出的原始指标文本，
     * 由 MetricsParser 解析后构建画像。
     *
     * @param accountId 账号 ID
     * @param request   请求体（含原始指标文本）
     * @return 201 + 构建的受众画像
     */
    @Operation(summary = "从指标文本构建画像", description = "从平台导出的原始指标文本解析并构建受众画像")
    @PostMapping("/{accountId}/from-metrics")
    public ResponseEntity<AgentResponse<AudienceProfile>> buildFromMetrics(
            @PathVariable String accountId,
            @Valid @RequestBody MetricsRequest request) {
        log.info("从指标文本构建画像: accountId={}, textLength={}",
                accountId, request.rawMetrics() == null ? 0 : request.rawMetrics().length());
        AudienceProfile profile = profileService.buildProfileFromMetrics(accountId, request.rawMetrics());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hasData", profile.hasData());
        metadata.put("source", "metrics-text");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentResponse.success(STAGE, profile, metadata));
    }

    /**
     * 获取画像摘要（人类可读）。
     *
     * <p>将结构化画像转为自然语言摘要，便于人工查阅或注入到 Agent Prompt 中。
     *
     * @param accountId 账号 ID
     * @return 200 + 摘要文本；不存在返回 404
     */
    @Operation(summary = "获取画像摘要", description = "生成人类可读的受众画像摘要文本")
    @GetMapping("/{accountId}/summary")
    public ResponseEntity<AgentResponse<String>> getSummary(@PathVariable String accountId) {
        AudienceProfile profile = profileService.getProfile(accountId);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure(STAGE, "受众画像不存在: " + accountId));
        }
        String summary = profileEnricher.generateAudienceSummary(profile);
        return ResponseEntity.ok(AgentResponse.success(STAGE, summary));
    }

    /**
     * 获取内容画像。
     *
     * @param accountId 账号 ID
     * @return 200 + 内容画像；不存在返回 404
     */
    @Operation(summary = "获取内容画像", description = "根据 accountId 获取内容画像")
    @GetMapping("/{accountId}/content")
    public ResponseEntity<AgentResponse<ContentProfile>> getContentProfile(@PathVariable String accountId) {
        ContentProfile profile = profileService.getContentProfile(accountId);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure(STAGE, "内容画像不存在: " + accountId));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hasData", profile.hasData());
        return ResponseEntity.ok(AgentResponse.success(STAGE, profile, metadata));
    }

    /**
     * 构建内容画像。
     *
     * @param accountId 账号 ID
     * @param platform  平台标识（可选）
     * @return 201 + 构建的内容画像
     */
    @Operation(summary = "构建内容画像", description = "从平台 API 拉取数据构建内容画像")
    @PostMapping("/{accountId}/content")
    public ResponseEntity<AgentResponse<ContentProfile>> buildContentProfile(
            @PathVariable String accountId,
            @RequestParam(value = "platform", required = false) String platform) {
        log.info("构建内容画像: accountId={}, platform={}", accountId, platform);
        ContentProfile profile = platform != null && !platform.isBlank()
                ? profileService.buildContentProfile(accountId, platform)
                : profileService.buildContentProfile(accountId);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hasData", profile.hasData());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentResponse.success(STAGE, profile, metadata));
    }

    /**
     * 获取内容画像摘要（人类可读）。
     *
     * @param accountId 账号 ID
     * @return 200 + 摘要文本；不存在返回 404
     */
    @Operation(summary = "获取内容画像摘要", description = "生成人类可读的内容画像摘要文本")
    @GetMapping("/{accountId}/content/summary")
    public ResponseEntity<AgentResponse<String>> getContentSummary(@PathVariable String accountId) {
        ContentProfile profile = profileService.getContentProfile(accountId);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure(STAGE, "内容画像不存在: " + accountId));
        }
        String summary = profileEnricher.generateContentSummary(profile);
        return ResponseEntity.ok(AgentResponse.success(STAGE, summary));
    }

    /**
     * 指标文本构建请求体。
     *
     * @param rawMetrics 原始指标文本（平台格式化输出）
     */
    public record MetricsRequest(
            @NotBlank(message = "rawMetrics 不能为空") String rawMetrics
    ) {
    }
}
