package com.contentops.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希（PBKDF2-HMAC-SHA256，不引入第三方依赖）。
 *
 * <p>使用 16 字节随机盐 + 65536 轮迭代 + 256 位输出，满足基础口令存储要求；
 * 生产环境如需更高标准可替换为 Argon2/bcrypt。
 */
@Slf4j
@Component
public class PasswordHasher {

    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;

    /** 哈希结果：盐（Base64）与哈希（Base64）。 */
    public record HashedPassword(String saltBase64, String hashBase64) {
    }

    public HashedPassword hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt);
        return new HashedPassword(
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash));
    }

    public boolean verify(String rawPassword, String saltBase64, String expectedHashBase64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            byte[] expected = Base64.getDecoder().decode(expectedHashBase64);
            byte[] actual = pbkdf2(rawPassword, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            log.warn("[Auth] 密码校验异常: {}", e.getMessage());
            return false;
        }
    }

    private byte[] pbkdf2(String rawPassword, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }
}
