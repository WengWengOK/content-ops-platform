package com.contentops.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenLogSanitizer 单元测试。
 *
 * <p>验证 P0 安全修复：日志中 access_token / api_key / Bearer token 的脱敏。
 */
@DisplayName("TokenLogSanitizer 日志脱敏测试")
class TokenLogSanitizerTest {

    @Nested
    @DisplayName("URL 查询参数脱敏")
    class QueryParamSanitization {

        @Test
        @DisplayName("access_token 查询参数应被脱敏")
        void accessToken_shouldBeMasked() {
            String input = "GET /cgi-bin/draft/add?access_token=ABC123XYZ HTTP/1.1";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("access_token=***"));
            assertFalse(result.contains("ABC123XYZ"));
        }

        @Test
        @DisplayName("api_key 查询参数应被脱敏")
        void apiKey_shouldBeMasked() {
            String input = "api_key=sk-1234567890abcdef";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("api_key=***"));
            assertFalse(result.contains("sk-1234567890abcdef"));
        }

        @Test
        @DisplayName("app_secret 查询参数应被脱敏")
        void appSecret_shouldBeMasked() {
            String input = "app_secret=mysecretvalue";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("app_secret=***"));
            assertFalse(result.contains("mysecretvalue"));
        }

        @Test
        @DisplayName("upload_token 查询参数应被脱敏")
        void uploadToken_shouldBeMasked() {
            String input = "upload_token=token123abc";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("upload_token=***"));
            assertFalse(result.contains("token123abc"));
        }

        @Test
        @DisplayName("password 查询参数应被脱敏")
        void password_shouldBeMasked() {
            String input = "password=contentops123";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("password=***"));
            assertFalse(result.contains("contentops123"));
        }
    }

    @Nested
    @DisplayName("Authorization Header 脱敏")
    class BearerTokenSanitization {

        @Test
        @DisplayName("Authorization Bearer token 应被脱敏")
        void bearerToken_shouldBeMasked() {
            String input = "Authorization: Bearer abc-123-xyz-456";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("Authorization: Bearer ***"));
            assertFalse(result.contains("abc-123-xyz-456"));
        }

        @Test
        @DisplayName("小写 authorization 也应被脱敏")
        void lowercaseBearerToken_shouldBeMasked() {
            String input = "authorization: bearer mytoken123";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("***"));
            assertFalse(result.contains("mytoken123"));
        }
    }

    @Nested
    @DisplayName("安全内容保留")
    class SafeContentPreservation {

        @Test
        @DisplayName("不含 token 的消息应保持不变")
        void safeMessage_shouldBeUnchanged() {
            String input = "Workflow wf-001 started at stage TOPIC_PLANNING";
            String result = TokenLogSanitizer.sanitize(input);
            assertEquals(input, result);
        }

        @Test
        @DisplayName("null 和空字符串应安全返回")
        void nullAndEmpty_shouldBeSafe() {
            assertNull(TokenLogSanitizer.sanitize(null));
            assertEquals("", TokenLogSanitizer.sanitize(""));
        }

        @Test
        @DisplayName("包含 media_id 等业务标识的 URL 应保留非 token 部分")
        void businessId_shouldBePreserved() {
            String input = "WeChat permanent material uploaded: media_id=abc123, url=https://cdn.example.com/img.png";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("media_id=abc123"));
            assertTrue(result.contains("url=https://cdn.example.com/img.png"));
        }
    }

    @Nested
    @DisplayName("复合场景")
    class CompositeScenarios {

        @Test
        @DisplayName("同时包含多个敏感参数的 URL 应全部脱敏")
        void multipleTokens_shouldAllBeMasked() {
            String input = "access_token=token1&api_key=key1&data=normal";
            String result = TokenLogSanitizer.sanitize(input);
            assertTrue(result.contains("access_token=***"));
            assertTrue(result.contains("api_key=***"));
            assertTrue(result.contains("data=normal"));
            assertFalse(result.contains("token1"));
            assertFalse(result.contains("key1"));
        }
    }
}
