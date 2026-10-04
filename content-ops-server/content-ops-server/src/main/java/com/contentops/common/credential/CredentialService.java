package com.contentops.common.credential;

import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台凭据服务：多账号 / 多租户归属的读写、解析与连通性探测。
 *
 * <h3>租户隔离规则</h3>
 * <ul>
 *   <li>凭据按 {@code owner_id} 归属；请求默认只能看到/使用自己租户的凭据；</li>
 *   <li>{@code owner_id} 为空的凭据视为「共享凭据」（开发模式或团队公共账号）；</li>
 *   <li>ADMIN 角色可跨租户访问；其它角色跨租户访问直接 403；</li>
 *   <li>令牌落库加密、出参一律掩码，任何接口都不会回传明文。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final PlatformCredentialRepository repository;
    private final CredentialCipher cipher;
    private final ObjectMapper objectMapper;

    /** 对外视图：令牌只给掩码。 */
    public record CredentialView(String credentialId, String ownerId, String platform, String accountName,
                                 String accountRef, String preset, String baseUrl, String accessTokenMasked,
                                 boolean enabled, boolean defaultCredential, LocalDateTime lastUsedAt,
                                 String lastError, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record CreateRequest(String platform, String accountName, String accountRef, String preset,
                                String baseUrl, String accessToken, Boolean enabled, Boolean defaultCredential) {
    }

    public record UpdateRequest(String accountName, String accountRef, String preset, String baseUrl,
                                String accessToken, Boolean enabled, Boolean defaultCredential) {
    }

    public record ProbeResult(boolean reachable, boolean authenticated, String baseUrl,
                              String healthDetail, String loginDetail, String hint) {
    }

    // ──────────────────────── CRUD ────────────────────────

    public CredentialView create(String ownerId, CreateRequest request) {
        if (request == null || isBlank(request.platform())) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "platform 不能为空");
        }
        if (isBlank(request.baseUrl())) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "baseUrl 不能为空（自建桥地址）");
        }
        boolean enabled = request.enabled() == null || request.enabled();
        boolean asDefault = Boolean.TRUE.equals(request.defaultCredential());

        PlatformCredential credential = PlatformCredential.builder()
                .credentialId(UUID.randomUUID().toString())
                .ownerId(ownerId)
                .platform(request.platform().trim().toLowerCase())
                .accountName(isBlank(request.accountName()) ? "未命名账号" : request.accountName().trim())
                .accountRef(request.accountRef())
                .preset(isBlank(request.preset()) ? "xhs-mcp" : request.preset().trim())
                .baseUrl(request.baseUrl().trim())
                .accessToken(cipher.encrypt(request.accessToken()))
                .enabled(enabled)
                .defaultCredential(asDefault)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        repository.insert(credential);
        if (asDefault) {
            repository.clearDefault(credential.getPlatform(), ownerId, credential.getCredentialId());
        }
        log.info("[Credential] 新增凭据: id={}, owner={}, platform={}, name={}, default={}",
                credential.getCredentialId(), ownerId, credential.getPlatform(),
                credential.getAccountName(), asDefault);
        return toView(credential);
    }

    public List<CredentialView> list(String ownerId, boolean includeShared) {
        List<PlatformCredential> all = repository.list(ownerId, 200);
        if (ownerId == null || includeShared) {
            return all.stream().map(this::toView).toList();
        }
        List<CredentialView> views = new ArrayList<>();
        for (PlatformCredential c : all) {
            if (c.getOwnerId() == null || c.getOwnerId().equals(ownerId) || isAdmin()) {
                views.add(toView(c));
            }
        }
        return views;
    }

    public CredentialView get(String credentialId, String ownerId) {
        return toView(requireCredential(credentialId, ownerId));
    }

    public CredentialView update(String credentialId, String ownerId, UpdateRequest request) {
        PlatformCredential credential = requireCredential(credentialId, ownerId);
        if (request != null) {
            if (!isBlank(request.accountName())) {
                credential.setAccountName(request.accountName().trim());
            }
            if (request.accountRef() != null) {
                credential.setAccountRef(request.accountRef());
            }
            if (!isBlank(request.preset())) {
                credential.setPreset(request.preset().trim());
            }
            if (!isBlank(request.baseUrl())) {
                credential.setBaseUrl(request.baseUrl().trim());
            }
            if (!isBlank(request.accessToken())) {
                credential.setAccessToken(cipher.encrypt(request.accessToken().trim()));
            }
            if (request.enabled() != null) {
                credential.setEnabled(request.enabled());
            }
            if (request.defaultCredential() != null) {
                credential.setDefaultCredential(request.defaultCredential());
            }
        }
        repository.update(credential);
        if (credential.isDefaultCredential()) {
            repository.clearDefault(credential.getPlatform(), credential.getOwnerId(), credential.getCredentialId());
        }
        log.info("[Credential] 更新凭据: id={}, owner={}", credentialId, ownerId);
        return toView(repository.findById(credentialId).orElse(credential));
    }

    public void delete(String credentialId, String ownerId) {
        PlatformCredential credential = requireCredential(credentialId, ownerId);
        repository.delete(credential.getCredentialId());
        log.info("[Credential] 删除凭据: id={}, owner={}", credentialId, ownerId);
    }

    // ──────────────────────── 解析（供采集/回复使用，返回解密后的令牌） ────────────────────────

    /**
     * 解析要使用的凭据：显式指定 → 该租户默认 → 共享（owner 为空）默认。
     * 返回空表示没有可用凭据，调用方可回退到全局配置。
     */
    public Optional<PlatformCredential> resolve(String ownerId, String platform, String credentialId) {
        String p = isBlank(platform) ? "xiaohongshu" : platform.trim().toLowerCase();
        if (!isBlank(credentialId)) {
            PlatformCredential explicit = repository.findById(credentialId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在: " + credentialId));
            checkOwner(explicit, ownerId);
            if (!explicit.isEnabled()) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "凭据已停用: " + credentialId);
            }
            return Optional.of(decrypted(explicit));
        }
        Optional<PlatformCredential> own = repository.findDefault(p, ownerId);
        if (own.isPresent()) {
            return own.map(this::decrypted);
        }
        if (ownerId != null) {
            Optional<PlatformCredential> shared = repository.findDefault(p, null);
            if (shared.isPresent()) {
                return shared.map(this::decrypted);
            }
        }
        return Optional.empty();
    }

    /** 定时任务用：某平台全部启用凭据（每个账号一份，用于通知增量）。 */
    public List<PlatformCredential> listEnabledDecrypted(String platform) {
        return repository.listEnabled(isBlank(platform) ? "xiaohongshu" : platform.trim().toLowerCase())
                .stream().map(this::decrypted).toList();
    }

    public void markUsed(String credentialId, String error) {
        if (!isBlank(credentialId)) {
            repository.markUsed(credentialId, error);
        }
    }

    /** 标记账号令牌健康状态（OK / AUTH_INVALID / ERROR），用于前端过期提醒。 */
    public void markTokenState(String credentialId, String tokenState, String error) {
        if (!isBlank(credentialId)) {
            repository.updateTokenState(credentialId, tokenState, error);
        }
    }

    // ──────────────────────── 探测 ────────────────────────

    /** 探测凭据可用性：/health 判断可达，/api/v1/login/status 判断账号是否已登录。 */
    public ProbeResult probe(String credentialId, String ownerId) {
        PlatformCredential credential = requireCredential(credentialId, ownerId);
        String baseUrl = credential.getBaseUrl() == null ? "" : credential.getBaseUrl().trim();
        String token;
        try {
            token = cipher.decryptOrThrow(credential.getAccessToken());
        } catch (CredentialDecryptException e) {
            return new ProbeResult(false, false, baseUrl, "",
                    "凭据解密失败", "凭据无法解密：请配置 contentops.security.credential-keys-old 后执行密钥轮换");
        }
        boolean reachable = false;
        String healthDetail = "";
        String loginDetail = "";
        boolean authenticated = false;

        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        try {
            healthDetail = client.get().uri("/health").retrieve().body(String.class);
            reachable = true;
        } catch (Exception e) {
            healthDetail = e.getMessage();
        }

        if (reachable && !isBlank(token)) {
            try {
                String raw = client.get().uri("/api/v1/login/status")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve().body(String.class);
                loginDetail = raw;
                authenticated = parseLoggedIn(raw);
            } catch (Exception e) {
                loginDetail = e.getMessage();
            }
        }

        String hint = !reachable
                ? "桥服务不可达：请确认 AUTH_TOKEN 对应的服务已启动、baseUrl 正确"
                : (authenticated ? "凭据可用：账号已登录" : "服务可达，但账号未登录或令牌不对（请先在桥端扫码登录 / 核对 token）");
        // 探测结果直接沉淀为令牌健康状态，前端据此高亮「需要更新令牌」
        repository.updateTokenState(credential.getCredentialId(),
                !reachable ? "ERROR" : (authenticated ? "OK" : "AUTH_INVALID"),
                reachable && authenticated ? null : hint);
        return new ProbeResult(reachable, authenticated, baseUrl, healthDetail, loginDetail, hint);
    }

    /** 兼容不同桥实现的登录状态字段。 */
    private boolean parseLoggedIn(String raw) {
        if (isBlank(raw)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            for (String field : List.of("logged_in", "loggedIn", "isLogin", "is_login", "login")) {
                JsonNode node = findField(root, field);
                if (node != null && node.isBoolean()) {
                    return node.asBoolean();
                }
            }
            JsonNode success = root.path("success");
            return success.isBoolean() && success.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode findField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null) {
                return direct;
            }
            for (JsonNode child : node) {
                JsonNode found = findField(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ──────────────────────── 内部 ────────────────────────

    private PlatformCredential requireCredential(String credentialId, String ownerId) {
        PlatformCredential credential = repository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在: " + credentialId));
        checkOwner(credential, ownerId);
        return credential;
    }

    private void checkOwner(PlatformCredential credential, String ownerId) {
        if (ownerId == null || isAdmin()) {
            return;
        }
        String credOwner = credential.getOwnerId();
        if (credOwner == null || credOwner.equals(ownerId)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问其它租户的凭据: " + credential.getCredentialId());
    }

    private boolean isAdmin() {
        String role = AuthContext.currentUserRole();
        return role != null && "ADMIN".equalsIgnoreCase(role);
    }

    /** 解密令牌；失败时打标记并抛出可操作的错误（绝不把密文当令牌用）。 */
    private PlatformCredential decrypted(PlatformCredential credential) {
        try {
            credential.setAccessToken(cipher.decryptOrThrow(credential.getAccessToken()));
            return credential;
        } catch (CredentialDecryptException e) {
            repository.markUsed(credential.getCredentialId(), "凭据解密失败：" + e.getMessage());
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "凭据无法解密（" + credential.getAccountName() + "）：" + e.getMessage()
                            + "；请把旧密钥加入 contentops.security.credential-keys-old 后执行密钥轮换");
        }
    }

    /** 密钥轮换：把历史密钥加密的凭据重新用当前密钥加密。 */
    public RotationReport rotate(String ownerId) {
        if (!cipher.isEncryptionEnabled()) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "未配置 contentops.security.credential-key，无需轮换");
        }
        List<PlatformCredential> all = repository.list(ownerId, 500);
        int total = all.size();
        int rotated = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (PlatformCredential credential : all) {
            String stored = credential.getAccessToken();
            if (stored == null || stored.isBlank()) {
                skipped++;
                continue;
            }
            if (!cipher.needsRotation(stored)) {
                skipped++;
                continue;
            }
            try {
                String plain = cipher.decryptOrThrow(stored);
                credential.setAccessToken(cipher.encrypt(plain));
                repository.update(credential);
                rotated++;
            } catch (CredentialDecryptException e) {
                failures.add(credential.getAccountName() + "(" + credential.getCredentialId() + "): "
                        + e.getMessage());
                repository.markUsed(credential.getCredentialId(), "轮换失败：" + e.getMessage());
            }
        }
        log.info("[Credential] 密钥轮换完成: keyId={}, 总数={}, 已轮换={}, 跳过={}, 失败={}",
                cipher.currentKeyId(), total, rotated, skipped, failures.size());
        return new RotationReport(cipher.currentKeyId(), total, rotated, skipped,
                failures.size(), failures);
    }

    /** 密钥轮换报告。 */
    public record RotationReport(String keyId, int total, int rotated, int skipped, int failed,
                                 List<String> failures) {
    }

    private CredentialView toView(PlatformCredential credential) {
        return new CredentialView(credential.getCredentialId(), credential.getOwnerId(), credential.getPlatform(),
                credential.getAccountName(), credential.getAccountRef(), credential.getPreset(),
                credential.getBaseUrl(), cipher.mask(credential.getAccessToken()), credential.isEnabled(),
                credential.isDefaultCredential(), credential.getLastUsedAt(), credential.getLastError(),
                credential.getCreatedAt(), credential.getUpdatedAt());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}