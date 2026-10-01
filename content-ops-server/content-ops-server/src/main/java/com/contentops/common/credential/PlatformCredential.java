package com.contentops.common.credential;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台账号凭据（多账号 / 多租户归属）。
 *
 * <p>一条凭据 = 某个租户（ownerId）在某平台上的一账号：
 * 对接方式（preset）、服务地址（baseUrl）、访问令牌（accessToken，落库时加密）。
 * 采集、回复、通知拉取都按凭据走，避免多账号互相串数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "平台账号凭据")
public class PlatformCredential {

    @Schema(description = "凭据 ID")
    private String credentialId;

    @Schema(description = "归属租户（用户）ID；为空表示共享/开发模式")
    private String ownerId;

    @Schema(description = "平台：xiaohongshu 等")
    private String platform;

    @Schema(description = "账号展示名，如「主号·小红」")
    private String accountName;

    @Schema(description = "平台账号标识（可选，如小红书号）")
    private String accountRef;

    @Schema(description = "对接预设：xhs-mcp | custom")
    private String preset;

    @Schema(description = "服务地址（自建桥地址）")
    private String baseUrl;

    /** 访问令牌（落库为密文，服务层对外只返回掩码） */
    @Schema(description = "访问令牌（仅写入，读取返回掩码）")
    private String accessToken;

    @Schema(description = "是否启用")
    private boolean enabled;

    @Schema(description = "是否为该租户在该平台的默认凭据")
    private boolean defaultCredential;

    @Schema(description = "最近一次使用时间")
    private LocalDateTime lastUsedAt;

    @Schema(description = "最近一次错误")
    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}