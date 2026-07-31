package com.contentops.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ErrorCode 枚举与异常体系测试。
 *
 * <p>验证 P0-5 修复：
 * <ul>
 *   <li>ErrorCode 枚举的 HTTP 状态码映射正确</li>
 *   <li>BusinessException / SystemException 正确携带错误码</li>
 *   <li>异常消息模板渲染正确</li>
 * </ul>
 */
@DisplayName("异常体系测试")
class GlobalExceptionHandlerTest {

    @Nested
    @DisplayName("ErrorCode 枚举")
    class ErrorCodeEnumTest {

        @Test
        @DisplayName("WORKFLOW_NOT_FOUND 应映射到 404")
        void workflowNotFound_shouldMapTo404() {
            assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,
                    ErrorCode.WORKFLOW_NOT_FOUND.getHttpStatus());
        }

        @Test
        @DisplayName("WORKFLOW_NOT_AWAITING_REVIEW 应映射到 409")
        void notAwaitingReview_shouldMapTo409() {
            assertEquals(org.springframework.http.HttpStatus.CONFLICT,
                    ErrorCode.WORKFLOW_NOT_AWAITING_REVIEW.getHttpStatus());
        }

        @Test
        @DisplayName("所有 ErrorCode 都应有非空 code 和 message")
        void allErrorCodes_shouldHaveCodeAndMessage() {
            for (ErrorCode ec : ErrorCode.values()) {
                assertNotNull(ec.getCode(), "ErrorCode " + ec.name() + " code 不应为空");
                assertNotNull(ec.getMessageTemplate(), "ErrorCode " + ec.name() + " messageTemplate 不应为空");
                assertNotNull(ec.getHttpStatus(), "ErrorCode " + ec.name() + " httpStatus 不应为空");
            }
        }
    }

    @Nested
    @DisplayName("BusinessException")
    class BusinessExceptionTest {

        @Test
        @DisplayName("BusinessException 应携带 ErrorCode 和格式化消息")
        void businessException_shouldCarryErrorCode() {
            BusinessException ex = new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, "wf-123");
            assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("wf-123"));
        }

        @Test
        @DisplayName("BusinessException 应映射到 4xx HTTP 状态码")
        void businessException_shouldMapTo4xx() {
            BusinessException ex = new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, "test");
            assertTrue(ex.getErrorCode().getHttpStatus().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("SystemException")
    class SystemExceptionTest {

        @Test
        @DisplayName("SystemException 应映射到 5xx HTTP 状态码")
        void systemException_shouldMapTo5xx() {
            SystemException ex = new SystemException(ErrorCode.INTERNAL_ERROR, "test");
            assertTrue(ex.getErrorCode().getHttpStatus().is5xxServerError());
        }
    }
}
