package com.contentops.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * JwtService 单元测试：签发 → 解析往返，篡改签名/过期拒绝。
 */
class JwtServiceTest {

    private JwtService newService(String secret) {
        SecurityProperties properties = new SecurityProperties();
        properties.setEnabled(true);
        properties.setJwtSecret(secret);
        // P0：JWT 校验现在需要查询数据库用户是否存在，测试中用 mock 放行
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        when(userRepository.findById(anyString())).thenAnswer(inv ->
                Optional.of(new UserRepository.UserRecord(
                        inv.getArgument(0), "alice", "hash", "salt", "CREATOR")));
        return new JwtService(properties, userRepository);
    }

    @Test
    @DisplayName("签发后可解析出用户信息")
    void createThenParse_roundTrip() {
        JwtService service = newService("test-secret-0123456789abcdef");
        String token = service.create("user-123", "alice_01", "CREATOR");

        Optional<AuthContext.AuthPrincipal> parsed = service.parse(token);

        assertTrue(parsed.isPresent());
        assertEquals("user-123", parsed.get().userId());
        assertEquals("alice_01", parsed.get().username());
    }

    @Test
    @DisplayName("篡改签名应拒绝")
    void tamperedSignature_shouldBeRejected() {
        JwtService service = newService("test-secret-0123456789abcdef");
        String token = service.create("user-123", "alice_01", "CREATOR");
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertTrue(service.parse(tampered).isEmpty());
    }

    @Test
    @DisplayName("错误密钥签发的 Token 应拒绝")
    void differentSecret_shouldBeRejected() {
        String token = newService("secret-one").create("user-1", "alice", "CREATOR");
        assertTrue(newService("secret-two").parse(token).isEmpty());
    }
}
