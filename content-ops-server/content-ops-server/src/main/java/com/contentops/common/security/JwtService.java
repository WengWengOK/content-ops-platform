package com.contentops.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * 轻量 JWT（HS256）签发与校验，零第三方依赖。
 *
 * <p>仅用于本项目自身签发/校验；如需对接 OIDC/OAuth2 网关可替换实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final SecurityProperties properties;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 签发 Token。
     */
    public String create(String userId, String username, String role) {
        long now = System.currentTimeMillis() / 1000;
        long exp = now + properties.getJwtExpireMinutes() * 60L;
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + userId + "\",\"username\":\"" + username
                + "\",\"role\":\"" + (role == null ? "CREATOR" : role)
                + "\",\"iat\":" + now + ",\"exp\":" + exp + "}");
        String signingInput = header + "." + payload;
        return signingInput + "." + base64Url(hmac(signingInput));
    }

    /**
     * 校验并解析 Token；无效/过期返回 empty。
     */
    public Optional<AuthContext.AuthPrincipal> parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String expected = base64Url(hmac(parts[0] + "." + parts[1]));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            JsonNode payload = objectMapper.readTree(
                    Base64.getUrlDecoder().decode(parts[1]));
            long exp = payload.path("exp").asLong();
            if (exp > 0 && System.currentTimeMillis() / 1000 >= exp) {
                return Optional.empty();
            }
            String userId = payload.path("sub").asText();
            String username = payload.path("username").asText();
            // P0 修复：sub 所指向的用户必须存在于数据库（带 Caffeine 本地缓存），
            // 否则拒绝 token（覆盖用户被删除/状态变更等场景）。
            UserRepository.UserRecord userRecord = userRepository.findById(userId).orElse(null);
            if (userRecord == null) {
                log.warn("[Auth] Token sub 对应数据库用户不存在，拒绝: userId={}", userId);
                return Optional.empty();
            }
            // 角色以数据库为准（改角色即时生效，无需等待 token 过期）
            return Optional.of(new AuthContext.AuthPrincipal(userId, username, userRecord.role()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
