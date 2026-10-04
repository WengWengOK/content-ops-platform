package com.contentops.comment;

import com.contentops.common.credential.CredentialService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集器数据源策略测试：mock / api / auto(回退) 三种模式的行为。
 */
class CommentCollectorTest {

    private final CommentProperties properties = new CommentProperties();
    private final XhsCommentApiClient apiClient = mock(XhsCommentApiClient.class);
    private final CommentWatchRepository watchRepository = mock(CommentWatchRepository.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final XhsClientFactory clientFactory = mock(XhsClientFactory.class);
    private final CommentRelationService relationService = mock(CommentRelationService.class);
    private final CommentCollector collector =
            new CommentCollector(properties, apiClient, watchRepository, credentialService, clientFactory,
                    relationService);

    @Test
    @DisplayName("通知增量：评论类通知映射为 Comment（内部 ID 复用评论 ID），含 filtered 与新笔记纳管")
    void collectFromNotifications_mapsAndAutoWatches() {
        properties.setAutoAnalyze(true);
        when(apiClient.isNotificationConfigured()).thenReturn(true);
        when(apiClient.fetchNotifications(null, 20)).thenReturn(new XhsCommentApiClient.NotificationPage(
                "mentions", 1,
                List.of(
                        notification("n1", "评论", "c-1", "求同款链接！", "note-1", "xs-note-1", "桃桃"),
                        notification("n2", "点赞", null, null, "note-1", "xs-note-1", "路人"),
                        notification("n3", "回复", "c-2", "已收藏", "note-2", "xs-note-2", "老王"))));
        when(apiClient.fetchUnreadCounts()).thenReturn(Map.of("mentions", 3));
        when(watchRepository.findByWork("xiaohongshu", "note-1", "owner-1")).thenReturn(Optional.empty());
        when(watchRepository.findByWork("xiaohongshu", "note-2", "owner-1")).thenReturn(Optional.empty());

        CommentCollector.NotificationCollectionResult result =
                collector.collectFromNotifications("owner-1", null, 20);

        assertThat(result.source()).isEqualTo("api");
        assertThat(result.tab()).isEqualTo("mentions");
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.commentsOrEmpty()).hasSize(2);
        Comment first = result.commentsOrEmpty().get(0);
        assertThat(first.getCommentId()).isEqualTo("xhs-c-1");
        assertThat(first.getPlatformCommentId()).isEqualTo("c-1");
        assertThat(first.getWorkId()).isEqualTo("note-1");
        assertThat(first.getAuthor()).isEqualTo("桃桃");
        assertThat(result.createdWatches()).isEqualTo(2);
        assertThat(result.unread()).containsEntry("mentions", 3);
        verify(watchRepository, times(2)).insert(any(CommentWatch.class));
    }

    @Test
    @DisplayName("通知增量：已监控笔记不重复纳管")
    void collectFromNotifications_existingWatchNotDuplicated() {
        when(apiClient.isNotificationConfigured()).thenReturn(true);
        when(apiClient.fetchNotifications(null, 20)).thenReturn(new XhsCommentApiClient.NotificationPage(
                "mentions", 0,
                List.of(notification("n1", "评论", "c-1", "内容", "note-1", "xs-note-1", "桃桃"))));
        when(apiClient.fetchUnreadCounts()).thenReturn(Map.of());
        when(watchRepository.findByWork("xiaohongshu", "note-1", "owner-1"))
                .thenReturn(Optional.of(CommentWatch.builder().watchId("w1").build()));

        CommentCollector.NotificationCollectionResult result =
                collector.collectFromNotifications("owner-1", null, 20);

        assertThat(result.createdWatches()).isZero();
        verify(watchRepository, never()).insert(any(CommentWatch.class));
    }

    @Test
    @DisplayName("通知接口未配置：返回 none 与提示，不抛错")
    void collectFromNotifications_notConfigured_returnsNone() {
        when(apiClient.isNotificationConfigured()).thenReturn(false);
        when(apiClient.notificationStatusHint()).thenReturn("真实接口未启用");

        CommentCollector.NotificationCollectionResult result =
                collector.collectFromNotifications("owner-1", null, 20);

        assertThat(result.source()).isEqualTo("none");
        assertThat(result.commentsOrEmpty()).isEmpty();
        assertThat(result.fallbackReason()).contains("未启用");
    }

    private XhsCommentApiClient.XhsNotification notification(String id, String type, String commentId,
                                                            String text, String feedId, String feedToken,
                                                            String nickname) {
        return new XhsCommentApiClient.XhsNotification(id, type, "标题", LocalDateTime.now(),
                "u-" + id, nickname, commentId, text, feedId, feedToken, "笔记标题");
    }
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
                                LocalDateTime.of(2025, 1, 1, 12, 0), null, "u-1")), null, false));

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