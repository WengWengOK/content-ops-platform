package com.contentops.common.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 凭据加密测试：v2 格式、密钥轮换、旧密钥兼容、解密失败必须显式报错。
 */
class CredentialCipherTest {

    private static final String OLD_KEY = "old-key-2026-09";
    private static final String NEW_KEY = "new-key-2026-10";

    @Test
    @DisplayName("加密：v2 格式带 keyId，可解密，掩码只留末 4 位")
    void encryptDecryptAndMask() {
        CredentialCipher cipher = new CredentialCipher(NEW_KEY, "k-2026-10", "");

        String encrypted = cipher.encrypt("sk-secret-token-1234");

        assertThat(cipher.isEncryptionEnabled()).isTrue();
        assertThat(encrypted).startsWith("enc:v2:k-2026-10:").doesNotContain("sk-secret-token-1234");
        assertThat(cipher.decryptOrThrow(encrypted)).isEqualTo("sk-secret-token-1234");
        assertThat(cipher.mask(encrypted)).isEqualTo("****1234");
        assertThat(cipher.needsRotation(encrypted)).isFalse();
    }

    @Test
    @DisplayName("轮换：旧密钥密文可解密，且被识别为需要轮换")
    void oldKeyCiphertextNeedsRotation() {
        CredentialCipher oldCipher = new CredentialCipher(OLD_KEY, "k-2026-09", "");
        String oldCiphertext = oldCipher.encrypt("token-abc-9999");

        CredentialCipher newCipher = new CredentialCipher(NEW_KEY, "k-2026-10", "k-2026-09=" + OLD_KEY);

        assertThat(newCipher.decryptOrThrow(oldCiphertext)).isEqualTo("token-abc-9999");
        assertThat(newCipher.needsRotation(oldCiphertext)).isTrue();

        String reEncrypted = newCipher.encrypt(newCipher.decryptOrThrow(oldCiphertext));
        assertThat(reEncrypted).startsWith("enc:v2:k-2026-10:");
        assertThat(newCipher.needsRotation(reEncrypted)).isFalse();
    }

    @Test
    @DisplayName("缺少旧密钥时：解密显式失败，掩码不泄露密文")
    void missingOldKeyFailsLoudly() {
        CredentialCipher oldCipher = new CredentialCipher(OLD_KEY, "k-2026-09", "");
        String oldCiphertext = oldCipher.encrypt("token-should-not-leak");

        CredentialCipher newCipher = new CredentialCipher(NEW_KEY, "k-2026-10", "");

        CredentialDecryptException ex = catchThrowableOfType(
                () -> newCipher.decryptOrThrow(oldCiphertext), CredentialDecryptException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("k-2026-09");
        assertThat(newCipher.mask(oldCiphertext)).isEqualTo("****(无法解密)");
    }

    @Test
    @DisplayName("兼容 v1 旧格式（无 keyId）：用当前密钥可解")
    void legacyV1FormatStillWorks() {
        CredentialCipher cipher = new CredentialCipher(NEW_KEY, "k-2026-10", "");
        String v2 = cipher.encrypt("legacy-token");
        String v1 = "enc:v1:" + v2.substring(v2.indexOf(':', "enc:v2:".length()) + 1);

        assertThat(cipher.needsRotation(v1)).isTrue();
        assertThat(cipher.decryptOrThrow(v1)).isEqualTo("legacy-token");
    }

    @Test
    @DisplayName("未配置密钥时退化为明文（仅开发），掩码仍生效")
    void withoutKeyFallsBackToPlaintext() {
        CredentialCipher cipher = new CredentialCipher("", "", "");

        String stored = cipher.encrypt("plain-token-9876");

        assertThat(cipher.isEncryptionEnabled()).isFalse();
        assertThat(stored).isEqualTo("plain-token-9876");
        assertThat(cipher.mask(stored)).isEqualTo("****9876");
        assertThat(cipher.needsRotation(stored)).isFalse();
    }
}