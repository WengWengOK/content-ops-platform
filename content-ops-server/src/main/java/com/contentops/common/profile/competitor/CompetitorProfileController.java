package com.contentops.common.profile.competitor;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.profile.competitor.CompetitorComparator.ComparisonReport;
import com.contentops.common.profile.competitor.CompetitorMonitorService.MonitorTask;
import com.contentops.common.profile.competitor.CompetitorProfileService.CompetitorMeta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * 竞品画像 REST API 控制器（P0 定向竞品监控）。
 *
 * <p>提供竞品监控的全生命周期管理端点：添加 / 列出 / 查看 / 对标 / 移除 / 刷新，
 * 将「通用搜索」能力升级为「定向竞品监控」的对外入口。
 *
 * <p>所有端点统一返回 {@link AgentResponse} 包装，便于与平台既有 Agent 服务风格对齐。
 *
 * <p>端点清单：
 * <ul>
 *   <li>{@code POST   /api/v1/competitors}            —— 添加竞品监控</li>
 *   <li>{@code GET    /api/v1/competitors}            —— 列出所有竞品</li>
 *   <li>{@code GET    /api/v1/competitors/{id}}       —— 获取竞品画像</li>
 *   <li>{@code GET    /api/v1/competitors/{id}/comparison} —— 获取对标分析</li>
 *   <li>{@code DELETE /api/v1/competitors/{id}}       —— 移除竞品</li>
 *   <li>{@code POST   /api/v1/competitors/{id}/refresh}     —— 手动刷新画像</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/competitors")
@RequiredArgsConstructor
@Tag(name = "竞品画像", description = "定向竞品监控与对标分析 API")
public class CompetitorProfileController {

    private static final String STAGE = "competitor-profile";

    private final CompetitorProfileService profileService;
    private final CompetitorMonitorService monitorService;
    private final CompetitorComparator comparator;

    // ════════════════════════════════════════════════════════════════
    // 端点
    // ════════════════════════════════════════════════════════════════

    /**
     * 添加竞品监控 —— 构建初始画像并注册定时监控任务。
     *
     * @param request 添加竞品请求
     * @return 监控任务信息
     */
    @Operation(summary = "添加竞品监控", description = "为指定竞品建立画像与定时监控任务")
    @PostMapping
    public AgentResponse<MonitorTask> addCompetitor(@Valid @RequestBody AddCompetitorRequest request) {
        log.info("Add competitor request: accountId={}, platform={}, niche={}",
                request.competitorAccountId(), request.platform(), request.niche());
        try {
            if (isBlank(request.competitorAccountId()) || isBlank(request.platform())) {
                return AgentResponse.failure(STAGE, "competitorAccountId 与 platform 不可为空");
            }
            MonitorTask task = monitorService.addMonitor(
                    request.competitorAccountId(),
                    request.platform(),
                    request.niche(),
                    request.monitorFrequencyHours());
            return AgentResponse.success(STAGE, task);
        } catch (Exception e) {
            log.error("Failed to add competitor: {}", request.competitorAccountId(), e);
            return AgentResponse.failure(STAGE, e.getMessage());
        }
    }

    /**
     * 列出所有竞品（可按领域过滤）。
     *
     * @param niche 领域过滤条件（可选）
     * @return 竞品元信息列表
     */
    @Operation(summary = "列出所有竞品", description = "列出已注册竞品，支持按领域过滤")
    @GetMapping
    public AgentResponse<List<CompetitorMeta>> listCompetitors(
            @Parameter(description = "领域过滤条件（可选）")
            @RequestParam(required = false) String niche) {
        try {
            List<CompetitorMeta> competitors = profileService.listCompetitors(niche);
            return AgentResponse.success(STAGE, competitors);
        } catch (Exception e) {
            log.error("Failed to list competitors", e);
            return AgentResponse.failure(STAGE, e.getMessage());
        }
    }

    /**
     * 获取竞品画像。
     *
     * @param id 竞品账号 ID
     * @return 竞品画像
     */
    @Operation(summary = "获取竞品画像", description = "获取指定竞品的四层完整画像")
    @GetMapping("/{id}")
    public AgentResponse<CompetitorProfile> getProfile(@PathVariable String id) {
        try {
            CompetitorProfile profile = profileService.getProfile(id);
            if (profile == null) {
                return AgentResponse.failure(STAGE, "竞品不存在或尚未构建画像: " + id);
            }
            return AgentResponse.success(STAGE, profile);
        } catch (Exception e) {
            log.error("Failed to get competitor profile: {}", id, e);
            return AgentResponse.failure(STAGE, e.getMessage());
        }
    }

    /**
     * 获取对标分析 —— 将指定竞品与我方账号进行多维对标。
     *
     * @param id          竞品账号 ID
     * @param myAccountId 我方账号 ID
     * @param myPlatform  我方平台
     * @param myNiche     我方领域
     * @return 完整对标报告
     */
    @Operation(summary = "获取对标分析", description = "将竞品与我方账号进行指标差距、选题重叠、风格相似与竞争烈度对标")
    @GetMapping("/{id}/comparison")
    public AgentResponse<ComparisonReport> getComparison(
            @PathVariable String id,
            @Parameter(description = "我方账号 ID", required = true)
            @RequestParam String myAccountId,
            @Parameter(description = "我方平台")
            @RequestParam(required = false) String myPlatform,
            @Parameter(description = "我方领域")
            @RequestParam(required = false) String myNiche) {
        try {
            CompetitorProfile competitorProfile = profileService.getProfile(id);
            if (competitorProfile == null) {
                return AgentResponse.failure(STAGE, "竞品不存在或尚未构建画像: " + id);
            }
            // 我方画像：优先取缓存，未构建则即时构建
            CompetitorProfile myProfile = profileService.getProfile(myAccountId);
            if (myProfile == null) {
                String platform = isBlank(myPlatform) ? competitorProfile.platform() : myPlatform;
                String niche = isBlank(myNiche) ? competitorProfile.niche() : myNiche;
                myProfile = profileService.buildProfile(myAccountId, platform, niche);
            }
            ComparisonReport report = comparator.compare(myProfile, competitorProfile);
            return AgentResponse.success(STAGE, report);
        } catch (Exception e) {
            log.error("Failed to generate comparison for competitor: {}", id, e);
            return AgentResponse.failure(STAGE, e.getMessage());
        }
    }

    /**
     * 移除竞品 —— 同时移除监控任务与缓存。
     *
     * @param id 竞品账号 ID
     * @return 移除结果
     */
    @Operation(summary = "移除竞品", description = "移除竞品监控任务与缓存画像")
    @DeleteMapping("/{id}")
    public AgentResponse<Map<String, Object>> removeCompetitor(@PathVariable String id) {
        try {
            monitorService.removeMonitor(id);
            CompetitorMeta removed = profileService.removeCompetitor(id);
            if (removed == null) {
                return AgentResponse.failure(STAGE, "竞品不存在: " + id);
            }
            return AgentResponse.success(STAGE, Map.of(
                    "removed", true,
                    "competitorAccountId", removed.competitorAccountId(),
                    "platform", removed.platform() != null ? removed.platform() : ""));
        } catch (Exception e) {
            log.error("Failed to remove competitor: {}", id, e);
            return AgentResponse.failure(STAGE, e.getMessage());
        }
    }

    /**
     * 手动刷新竞品画像 —— 重新拉取最新数据并更新画像。
     *
     * @param id 竞品账号 ID
     * @return 刷新后的画像
     */
    @Operation(summary = "手动刷新画像", description = "重新拉取竞品最新数据并更新画像")
    @PostMapping("/{id}/refresh")
    public AgentResponse<CompetitorProfile> refreshProfile(@PathVariable String id) {
        log.info("Refresh competitor profile: {}", id);
        try {
            CompetitorProfile refreshed = profileService.updateProfile(id);
            if (refreshed == null) {
                return AgentResponse.failure(STAGE, "竞品不存在或尚未注册: " + id);
            }
            return AgentResponse.success(STAGE, refreshed);
        } catch (Exception e) {
            log.error("Failed to refresh competitor profile: {}", id, e);
            return AgentResponse.failure(STAGE, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 请求体
    // ════════════════════════════════════════════════════════════════

    /**
     * 添加竞品请求体。
     *
     * @param competitorAccountId 竞品账号 ID
     * @param platform            平台标识
     * @param niche               所属领域
     * @param monitorFrequencyHours 监控频率（小时），<=0 使用默认值
     */
    public record AddCompetitorRequest(
            @NotBlank(message = "competitorAccountId 不能为空") String competitorAccountId,
            @NotBlank(message = "platform 不能为空") String platform,
            String niche,
            int monitorFrequencyHours
    ) {
    }

    // ──────────────────── 辅助方法 ────────────────────

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
