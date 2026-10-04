package com.contentops.common.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭据加密（支持密钥轮换）。
 *
 * <h3>密文格式</h3>
 * <ul>
 *   <li>{@code enc:v2:<keyId>:<base64(iv|ct|tag)>} — 当前格式，带 keyId，便于多密钥并存</li>
 *   <li>{@code enc:v1:<base64(iv|ct|tag)>} — 旧格式（无 keyId），按「当前密钥 → 旧密钥列表」顺序尝试解密</li>
 *   <li>未加密的明文（本地开发写入）原样返回</li>
 * </ul>
 *
 * <h3>轮换流程</h3>
 * <ol>
 *   <li>把旧密钥写进 {@code contentops.security.credential-keys-old}（逗号分隔），新密钥写入
 *       {@code contentops.security.credential-key}，重启；</li>
 *   <li>调用 {@code POST /api/v1/platform-credentials/rotate} 批量「解密→用新密钥重新加密」；</li>
 *   <li>确认轮换报告里 failed=0 后，即可移除旧密钥配置。</li>
 * </ol>
 *
 * <p>解密失败会抛 {@link CredentialDecryptException}，调用方必须显式处理（提示「密钥不匹配，请补旧密钥或执行轮换」）。
 */
@Slf4j
@Component
public class CredentialCipher {

    private static final String V2_PREFIX = "enc:v2:";
    private static final String V1_PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final boolean encryptionEnabled;
    private final String keyId;
    private final byte[] currentKey;
    /** keyId → key（含当前与历史密钥，便于逐步轮换） */
    private final Map<String, byte[]> keys = new LinkedHashMap<>();

    public CredentialCipher(@Value("${contentops.security.credential-key:}") String keyMaterial,
                            @Value("${contentops.security.credential-key-id:}") String configuredKeyId,
                            @Value("${contentops.security.credential-keys-old:}") String oldKeyMaterials) {
        boolean enabled = keyMaterial != null && !keyMaterial.isBlank();
        this.encryptionEnabled = enabled;
        this.keyId = enabled
                ? (configuredKeyId != null && !configuredKeyId.isBlank() ? configuredKeyId.trim() : keyHash(keyMaterial))
                : "";
        this.currentKey = enabled ? sha256(keyMaterial) : new byte[0];
        if (enabled) {
            keys.put(keyId, currentKey);
            for (String old : splitKeys(oldKeyMaterials)) {
                // 支持两种写法：`keyId=密钥材料`（推荐，显式 keyId 加密的密文需要）或只写密钥材料
                int sep = old.indexOf('=');
                if (sep > 0 && sep < old.length() - 1) {
                    String oldKeyId = old.substring(0, sep).trim();
                    String material = old.substring(sep + 1).trim();
                    byte[] oldKey = sha256(material);
                    keys.putIfAbsent(oldKeyId, oldKey);
                    keys.putIfAbsent(keyHash(material), oldKey);
                } else {
                    keys.putIfAbsent(keyHash(old), sha256(old));
                }
            }
            log.info("[Credential] 凭据加密已启用（AES-GCM, keyId={}, 历史密钥数={}）",
                    keyId, Math.max(0, keys.size() - 1));
        } else {
            log.warn("[Credential] 未配置 contentops.security.credential-key，凭据将以明文落库（仅建议本地开发）");
        }
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public String currentKeyId() {
        return keyId;
    }

    /** 已注册密钥总数（含当前与历史） */
    public int registeredKeyCount() {
        return keys.size();
    }

    /** 该值是否需要轮换（密文且不是当前 keyId / 还是 v1 格式）。 */
    public boolean needsRotation(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return false;
        }
        if (stored.startsWith(V1_PREFIX)) {
            return true;
        }
        Parsed parsed = parse(stored);
        return parsed == null || !keyId.equals(parsed.keyId());
    }

    /** 加密明文；已加密或空值原样返回。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain) || !encryptionEnabled) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(currentKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return V2_PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密别名：语义与 {@link #decryptOrThrow} 一致（解不开即抛异常，绝不返回密文）。 */
    public String decrypt(String stored) {
        return decryptOrThrow(stored);
    }

    /** 解密（显式失败）：密文解不开时抛异常，绝不返回密文。 */
    public String decryptOrThrow(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        List<byte[]> candidates = new ArrayList<>();
        if (stored.startsWith(V2_PREFIX)) {
            Parsed parsed = parse(stored);
            if (parsed == null) {
                throw new CredentialDecryptException("凭据密文格式非法: " + preview(stored));
            }
            byte[] key = keys.get(parsed.keyId());
            if (key == null) {
                throw new CredentialDecryptException("凭据密钥缺失：keyId=" + parsed.keyId()
                        + "，请把该密钥加入 contentops.security.credential-keys-old 或恢复原密钥后再轮换");
            }
            candidates.add(key);
            return decryptWith(stored, parsed, candidates);
        }
        // v1：无 keyId，按「当前 → 旧密钥」顺序尝试
        candidates.addAll(keys.values());
        Parsed parsed = new Parsed(null, stored.substring(V1_PREFIX.length()));
        for (byte[] key : candidates) {
            try {
                return doDecrypt(key, parsed.payload());
            } catch (Exception ignored) {
                // 继续尝试下一个密钥
            }
        }
        throw new CredentialDecryptException(
                "凭据解密失败：没有能解开该密文的密钥（密钥已轮换且缺少旧密钥），请配置 contentops.security.credential-keys-old");
    }

    /** 掩码展示：解不开时返回明确占位符，绝不回传密文。 */
    public String mask(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        if (!isEncrypted(stored)) {
            return stored.length() <= 4 ? "****" : "****" + stored.substring(stored.length() - 4);
        }
        try {
            String plain = decryptOrThrow(stored);
            return plain.length() <= 4 ? "****" : "****" + plain.substring(plain.length() - 4);
        } catch (CredentialDecryptException e) {
            return "****(无法解密)";
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && (value.startsWith(V2_PREFIX) || value.startsWith(V1_PREFIX));
    }

    // ──────────────────────── 内部 ────────────────────────

    private String decryptWith(String stored, Parsed parsed, List<byte[]> candidates) {
        for (byte[] key : candidates) {
            try {
                return doDecrypt(key, parsed.payload());
            } catch (Exception ignored) {
                // 只有一个候选时也会落到下面抛错
            }
        }
        throw new CredentialDecryptException("凭据解密失败（keyId=" + parsed.keyId() + "），可能是密钥内容已变更");
    }

    private String doDecrypt(byte[] key, String payload) throws Exception {
        byte[] combined = Base64.getDecoder().decode(payload);
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private Parsed parse(String stored) {
        if (!stored.startsWith(V2_PREFIX)) {
            return null;
        }
        String body = stored.substring(V2_PREFIX.length());
        int sep = body.indexOf(':');
        if (sep <= 0 || sep == body.length() - 1) {
            return null;
        }
        return new Parsed(body.substring(0, sep), body.substring(sep + 1));
    }

    private List<String> splitKeys(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) {
                list.add(part.trim());
            }
        }
        return list;
    }

    private String keyHash(String material) {
        try {
            byte[] digest = sha256(material);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算密钥指纹失败", e);
        }
    }

    private byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化凭据加密密钥失败", e);
        }
    }

    private String preview(String value) {
        return value.length() <= 24 ? value : value.substring(0, 24) + "...";
    }

    private record Parsed(String keyId, String payload) {
    }
}