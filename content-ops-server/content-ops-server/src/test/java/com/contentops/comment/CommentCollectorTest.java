package com.contentops.comment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集器数据源策略测试：mock / api / auto(回退) 三种模式的行为。
 */
class CommentCollectorTest {

    private final CommentProperties properties = new CommentProperties();
    private final XhsCommentApiClient apiClient = mock(XhsCommentApiClient.class);
    private final CommentCollector collector = new CommentCollector(properties, apiClient);

    @Test
    @DisplayName("source=mock：直接使用模拟数据，标记数据源为 mock")
    void collect_mockSource_returnsMockData() {
        properties.setSource("mock");

        CommentCollector.CollectionResult result = collector.collect("note-1", "owner-1");

        assertThat(result.source()).isEqualTo("mock");
        assertThat(result.commentsOrEmpty()).isNotEmpty();
        assertThat(result.commentsOrEmpty()).allSatisfy(c ->
                assertThat(c.getPlatform()).isEqualTo("xiaohongshu"));
    }

    @Test
    @DisplayName("source=api 且未配置真实接口：直接抛错（避免把模拟数据当真实数据）")
    void collect_apiSourceWithoutConfig_throws() {
        properties.setSource("api");
        when(apiClient.isConfigured()).thenReturn(false);
        when(apiClient.statusHint()).thenReturn("真实评论接口未启用：contentops.comment.xiaohongshu.enabled=false");

        CommentSourceException ex = catchThrowableOfType(
                () -> collector.collect("note-1", "owner-1"), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("COMMENT_SOURCE_NOT_CONFIGURED");
        assertThat(ex.getMessage()).contains("未启用");
    }

    @Test
    @DisplayName("source=auto 且未配置：回退模拟数据并记录原因")
    void collect_autoWithoutConfig_fallsBackToMock() {
        properties.setSource("auto");
        when(apiClient.isConfigured()).thenReturn(false);
        when(apiClient.statusHint()).thenReturn("未配置评论接口地址");

        CommentCollector.CollectionResult result = collector.collect("note-1", "owner-1");

        assertThat(result.source()).isEqualTo("mock");
        assertThat(result.fallbackReason()).contains("未配置评论接口地址");
    }

    @Test
    @DisplayName("source=auto 且已配置：调用真实接口并映射为 Comment（ID 前缀 xhs-）")
    void collect_autoWithConfig_usesRealApi() {
        properties.setSource("auto");
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchPage(anyString(), any(), anyInt(), any())).thenReturn(
                new XhsCommentApiClient.FetchPage(List.of(
                        new XhsCommentApiClient.XhsComment("c-1", "小红", "求教程", 5,
                                LocalDateTime.of(2025, 1, 1, 12, 0), null)), null, false));

        CommentCollector.CollectionResult result = collector.collect("note-1", "owner-1");

        assertThat(result.source()).isEqualTo("api");
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.commentsOrEmpty()).hasSize(1);
        Comment comment = result.commentsOrEmpty().get(0);
        assertThat(comment.getCommentId()).isEqualTo("xhs-c-1");
        assertThat(comment.getAuthor()).isEqualTo("小红");
        assertThat(comment.getLikes()).isEqualTo(5);
        assertThat(comment.getReplyStatus()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("真实接口异常时按 fallback-to-mock 回退，并保留错误原因")
    void collect_apiFailure_fallsBackWithReason() {
        properties.setSource("auto");
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchPage(anyString(), any(), anyInt(), any()))
                .thenThrow(new CommentSourceException("COMMENT_SOURCE_FORBIDDEN", "无权限：该接口需开通权限"));

        CommentCollector.CollectionResult result = collector.collect("note-1", "owner-1");

        assertThat(result.source()).isEqualTo("mock");
        assertThat(result.fallbackReason()).contains("COMMENT_SOURCE_FORBIDDEN").contains("无权限");
    }

    @Test
    @DisplayName("非小红书平台：真实接口未接入，回退模拟数据")
    void collect_otherPlatform_fallsBackToMock() {
        properties.setSource("auto");
        when(apiClient.isConfigured()).thenReturn(true);

        CommentCollector.CollectionResult result = collector.collect("douyin", "work-1", "owner-1");

        assertThat(result.source()).isEqualTo("mock");
        assertThat(result.fallbackReason()).contains("COMMENT_PLATFORM_NOT_SUPPORTED");
    }
}