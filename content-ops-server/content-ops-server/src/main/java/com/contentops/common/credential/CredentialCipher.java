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
import java.util.Base64;

/**
 * 凭据加密：AES-GCM 加密落库，避免明文令牌进数据库。
 *
 * <p>密钥来自 {@code contentops.security.credential-key}（生产必须配置，建议 32+ 位随机串）。
 * 未配置密钥时退化为明文存储（仅用于本地开发），并在日志中明确告警。
 * 密文格式 {@code enc:v1:<base64(iv|cipherText|tag)>}，便于后续轮换算法。
 */
@Slf4j
@Component
public class CredentialCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final boolean encryptionEnabled;

    public CredentialCipher(@Value("${contentops.security.credential-key:}") String keyMaterial) {
        boolean enabled = keyMaterial != null && !keyMaterial.isBlank();
        this.encryptionEnabled = enabled;
        this.key = enabled ? sha256(keyMaterial) : new byte[0];
        if (enabled) {
            log.info("[Credential] 凭据加密已启用（AES-GCM）");
        } else {
            log.warn("[Credential] 未配置 contentops.security.credential-key，凭据将以明文落库（仅建议本地开发使用）");
        }
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    /** 加密明文令牌；已加密或空值原样返回。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain) || !encryptionEnabled) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密；明文或解密失败时返回原值（避免因密钥变更导致完全不可用）。 */
    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[Credential] 凭据解密失败（可能是密钥变更）: {}", e.getMessage());
            return stored;
        }
    }

    /** 掩码展示：只保留末 4 位，绝不回传明文。 */
    public String mask(String stored) {
        String plain = decrypt(stored);
        if (plain == null || plain.isBlank()) {
            return "";
        }
        if (plain.length() <= 4) {
            return "****";
        }
        return "****" + plain.substring(plain.length() - 4);
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化凭据加密密钥失败", e);
        }
    }
}