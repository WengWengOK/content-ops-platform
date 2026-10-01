package com.contentops.common.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialCipherTest {

    @Test
    @DisplayName("配置密钥时：加密落库、可解密、掩码只留末 4 位")
    void encryptDecryptAndMask() {
        CredentialCipher cipher = new CredentialCipher("unit-test-key-please-change");

        String encrypted = cipher.encrypt("sk-secret-token-1234");

        assertThat(cipher.isEncryptionEnabled()).isTrue();
        assertThat(encrypted).startsWith("enc:v1:").doesNotContain("sk-secret-token-1234");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-secret-token-1234");
        assertThat(cipher.mask(encrypted)).isEqualTo("****1234");
        assertThat(cipher.encrypt(encrypted)).isEqualTo(encrypted);
    }

    @Test
    @DisplayName("未配置密钥时退化为明文（仅开发），掩码仍然生效")
    void withoutKeyFallsBackToPlaintext() {
        CredentialCipher cipher = new CredentialCipher("");

        String stored = cipher.encrypt("plain-token-9876");

        assertThat(cipher.isEncryptionEnabled()).isFalse();
        assertThat(stored).isEqualTo("plain-token-9876");
        assertThat(cipher.mask(stored)).isEqualTo("****9876");
    }
}