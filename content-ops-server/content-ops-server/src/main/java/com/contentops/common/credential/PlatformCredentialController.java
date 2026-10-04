package com.contentops.common.credential;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.security.AuthContext;
import com.contentops.common.security.RequireRole;
import com.contentops.common.security.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台账号凭据接口（多账号 / 多租户）：供评论采集、回复、通知增量按账号选凭据。
 *
 * <p>安全约定：读取接口只返回令牌掩码；令牌写入后不再明文返回。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform-credentials")
@RequiredArgsConstructor
@RequireRole(UserRole.CREATOR)
@Tag(name = "平台账号凭据")
public class PlatformCredentialController {

    private final CredentialService credentialService;

    private String ownerId() {
        return AuthContext.currentUserId();
    }

    @PostMapping
    @Operation(summary = "新增平台账号凭据（令牌加密落库）")
    public AgentResponse<CredentialService.CredentialView> create(
            @RequestBody CredentialService.CreateRequest request) {
        return AgentResponse.success("credential", credentialService.create(ownerId(), request));
    }

    @GetMapping
    @Operation(summary = "我的平台账号凭据列表（令牌仅返回掩码）")
    public AgentResponse<List<CredentialService.CredentialView>> list(
            @RequestParam(required = false, defaultValue = "true") boolean includeShared) {
        return AgentResponse.success("credential", credentialService.list(ownerId(), includeShared));
    }

    @GetMapping("/{credentialId}")
    @Operation(summary = "凭据详情（令牌掩码）")
    public AgentResponse<CredentialService.CredentialView> get(@PathVariable String credentialId) {
        return AgentResponse.success("credential", credentialService.get(credentialId, ownerId()));
    }

    @PutMapping("/{credentialId}")
    @Operation(summary = "更新凭据（accessToken 传空表示不变）")
    public AgentResponse<CredentialService.CredentialView> update(
            @PathVariable String credentialId,
            @RequestBody CredentialService.UpdateRequest request) {
        return AgentResponse.success("credential",
                credentialService.update(credentialId, ownerId(), request));
    }

    @DeleteMapping("/{credentialId}")
    @Operation(summary = "删除凭据")
    public AgentResponse<java.util.Map<String, Object>> delete(@PathVariable String credentialId) {
        credentialService.delete(credentialId, ownerId());
        return AgentResponse.success("credential",
                java.util.Map.of("deleted", true, "credentialId", credentialId));
    }

    @PostMapping("/rotate")
    @RequireRole(UserRole.ADMIN)
    @Operation(summary = "密钥轮换：把历史密钥加密的凭据重新用当前密钥加密（仅管理员）")
    public AgentResponse<CredentialService.RotationReport> rotate() {
        return AgentResponse.success("credential", credentialService.rotate(null));
    }

    @PostMapping("/{credentialId}/probe")
    @Operation(summary = "探测凭据：桥是否可达、账号是否已登录")
    public AgentResponse<CredentialService.ProbeResult> probe(@PathVariable String credentialId) {
        return AgentResponse.success("credential", credentialService.probe(credentialId, ownerId()));
    }
}