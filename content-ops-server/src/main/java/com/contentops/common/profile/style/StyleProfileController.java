package com.contentops.common.profile.style;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.profile.style.StyleProfileManager.SimilarStyleMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 风格画像 REST API 控制器（P0 改造）。
 *
 * <p>提供风格画像的创建、查询、列表、风格指南、相似账号检索、刷新与删除端点，
 * 统一以 {@link AgentResponse} 包装响应，遵循平台标准返回格式。
 *
 * <h3>端点一览</h3>
 * <ul>
 *   <li>{@code POST   /api/v1/style-profiles} —— 创建风格画像</li>
 *   <li>{@code GET    /api/v1/style-profiles} —— 列出所有风格画像</li>
 *   <li>{@code GET    /api/v1/style-profiles/{accountId}} —— 获取风格画像</li>
 *   <li>{@code GET    /api/v1/style-profiles/{accountId}/guide} —— 获取风格指南</li>
 *   <li>{@code GET    /api/v1/style-profiles/similar/{accountId}} —— 查找相似风格账号</li>
 *   <li>{@code POST   /api/v1/style-profiles/{accountId}/refresh} —— 刷新画像</li>
 *   <li>{@code DELETE /api/v1/style-profiles/{accountId}} —— 删除画像</li>
 * </ul>
 *
 * @see StyleProfileManager
 * @see StyleEnricher
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/style-profiles")
@RequiredArgsConstructor
@Tag(name = "风格画像", description = "基于作品分析的风格特征提取与管理")
public class StyleProfileController {

    /** AgentResponse 中的阶段标识。 */
    private static final String STAGE = "style-profile";

    private final StyleProfileManager profileManager;
    private final StyleEnricher styleEnricher;

    /**
     * 创建风格画像。
     *
     * @param request 创建请求（accountId + 内容列表）
     * @return 201 + 创建的风格画像
     */
    @Operation(summary = "创建风格画像", description = "基于账号历史作品列表分析并创建风格画像")
    @PostMapping
    public ResponseEntity<AgentResponse<StyleProfile>> createProfile(
            @Valid @RequestBody CreateProfileRequest request) {
        log.info("创建风格画像: accountId={}, contentCount={}",
                request.accountId(), request.contents() == null ? 0 : request.contents().size());
        StyleProfile profile = profileManager.createProfile(request.accountId(), request.contents());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sampleCount", profile.sampleCount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentResponse.success(STAGE, profile, metadata));
    }

    /**
     * 获取指定账号的风格画像。
     *
     * @param accountId 账号 ID
     * @return 200 + 画像；不存在返回 404
     */
    @Operation(summary = "获取风格画像", description = "根据 accountId 获取风格画像")
    @GetMapping("/{accountId}")
    public ResponseEntity<AgentResponse<StyleProfile>> getProfile(@PathVariable String accountId) {
        Optional<StyleProfile> profile = profileManager.getProfile(accountId);
        return profile.map(p -> ResponseEntity.ok(AgentResponse.success(STAGE, p)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AgentResponse.failure(STAGE, "风格画像不存在: " + accountId)));
    }

    /**
     * 列出所有风格画像。
     *
     * @return 200 + 画像列表
     */
    @Operation(summary = "列出所有风格画像", description = "返回当前已创建的全部风格画像")
    @GetMapping
    public ResponseEntity<AgentResponse<List<StyleProfile>>> listProfiles() {
        List<StyleProfile> profiles = profileManager.listProfiles();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("total", profiles.size());
        return ResponseEntity.ok(AgentResponse.success(STAGE, profiles, metadata));
    }

    /**
     * 获取人类可读的风格指南。
     *
     * @param accountId 账号 ID
     * @return 200 + 风格指南文本；不存在返回 404
     */
    @Operation(summary = "获取风格指南", description = "生成人类可读的风格指南文本")
    @GetMapping("/{accountId}/guide")
    public ResponseEntity<AgentResponse<String>> getStyleGuide(@PathVariable String accountId) {
        Optional<StyleProfile> profile = profileManager.getProfile(accountId);
        if (profile.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure(STAGE, "风格画像不存在: " + accountId));
        }
        String guide = styleEnricher.generateStyleGuide(profile.get());
        return ResponseEntity.ok(AgentResponse.success(STAGE, guide));
    }

    /**
     * 查找风格相似的账号。
     *
     * @param accountId 目标账号 ID
     * @param limit     返回上限（可选，默认走配置）
     * @return 200 + 相似账号匹配列表
     */
    @Operation(summary = "查找相似风格账号", description = "基于风格签名向量检索风格相似的账号")
    @GetMapping("/similar/{accountId}")
    public ResponseEntity<AgentResponse<List<SimilarStyleMatch>>> findSimilar(
            @PathVariable String accountId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Optional<StyleProfile> profile = profileManager.getProfile(accountId);
        if (profile.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure(STAGE, "风格画像不存在: " + accountId));
        }
        int effectiveLimit = limit == null ? -1 : limit;
        List<SimilarStyleMatch> matches = profileManager.findSimilarStyle(profile.get(), effectiveLimit);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("count", matches.size());
        return ResponseEntity.ok(AgentResponse.success(STAGE, matches, metadata));
    }

    /**
     * 刷新指定账号的风格画像（重新持久化到 RAG 知识库并失效缓存）。
     *
     * @param accountId 账号 ID
     * @return 200 + 刷新后的画像；不存在返回 404
     */
    @Operation(summary = "刷新风格画像", description = "重新持久化画像到知识库并失效缓存")
    @PostMapping("/{accountId}/refresh")
    public ResponseEntity<AgentResponse<StyleProfile>> refresh(@PathVariable String accountId) {
        Optional<StyleProfile> profile = profileManager.refresh(accountId);
        return profile.map(p -> ResponseEntity.ok(AgentResponse.success(STAGE, p)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AgentResponse.failure(STAGE, "风格画像不存在: " + accountId)));
    }

    /**
     * 删除指定账号的风格画像。
     *
     * @param accountId 账号 ID
     * @return 200 删除成功；不存在返回 404
     */
    @Operation(summary = "删除风格画像", description = "删除指定账号的风格画像")
    @DeleteMapping("/{accountId}")
    public ResponseEntity<AgentResponse<Void>> deleteProfile(@PathVariable String accountId) {
        boolean deleted = profileManager.deleteProfile(accountId);
        if (deleted) {
            return ResponseEntity.ok(AgentResponse.success(STAGE, null));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AgentResponse.failure(STAGE, "风格画像不存在: " + accountId));
    }

    /**
     * 创建风格画像请求体。
     *
     * @param accountId 账号 ID（必填）
     * @param contents  历史作品正文列表
     */
    public record CreateProfileRequest(
            @NotBlank(message = "accountId 不能为空") String accountId,
            List<String> contents
    ) {}
}
