package com.contentops.comment;

import com.contentops.common.credential.CredentialService;
import com.contentops.common.observability.AlertForwardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 票据预警通知测试：去重、强制推送、消息内容。
 */
class TokenWarningNotifierTest {

    private CommentWatchRepository watchRepository;
    private AlertForwardingService alertForwardingService;
    private CredentialService credentialService;
    private CommentProperties properties;
    private TokenWarningNotifier notifier;

    @BeforeEach
    void setUp() {
        watchRepository = mock(CommentWatchRepository.class);
        alertForwardingService = mock(AlertForwardingService.class);
        credentialService = mock(CredentialService.class);
        properties = new CommentProperties();
        properties.getTokenNotify().setEnabled(true);
        properties.getTokenNotify().setRemindHours(24);
        WatchTokenHealthService healthService = new WatchTokenHealthService(properties);
        notifier = new TokenWarningNotifier(watchRepository, healthService, properties,
                alertForwardingService, credentialService);
        when(alertForwardingService.sendBusinessCard(anyString(), anyList(), any(), any()))
                .thenReturn(Map.of("feishu", Map.of("configured", true, "success", true, "httpStatus", 200)));
        when(credentialService.healthList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("首次发现「票据已失效」：投递一次并记录通知状态")
    void notifyExpiredOnce() {
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of(watch("w1", "EXPIRED")));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.notified()).isEqualTo(1);
        assertThat(String.join("\n", result.items()))
                .contains("【笔记票据 xsec_token】")
                .contains("票据已失效")
                .contains("观测有效期 5 天");
        verify(alertForwardingService).sendBusinessCard(anyString(), anyList(), any(), any());
        verify(watchRepository).markTokenNotified("w1", "EXPIRED");
    }

    @Test
    @DisplayName("同一状态且未到重复提醒间隔：不重复投递")
    void notifyDedupesWithinRemindWindow() {
        CommentWatch notified = watch("w1", "EXPIRED");
        notified.setTokenNotifiedState("EXPIRED");
        notified.setTokenNotifiedAt(LocalDateTime.now().minusHours(1));
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of(notified));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.notified()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(alertForwardingService, never()).sendBusinessCard(anyString(), anyList(), any(), any());
    }

    @Test
    @DisplayName("超过重复提醒间隔：再次投递")
    void notifyRepeatsAfterRemindWindow() {
        CommentWatch notified = watch("w1", "EXPIRING");
        notified.setTokenNotifiedState("EXPIRING");
        notified.setTokenNotifiedAt(LocalDateTime.now().minusHours(30));
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of(notified));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, false);

        assertThat(result.notified()).isEqualTo(1);
    }

    @Test
    @DisplayName("force=true：忽略去重强制推送（运维面板验证用）")
    void notifyForceIgnoresDedupe() {
        CommentWatch notified = watch("w1", "EXPIRED");
        notified.setTokenNotifiedState("EXPIRED");
        notified.setTokenNotifiedAt(LocalDateTime.now());
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of(notified));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, true);

        assertThat(result.notified()).isEqualTo(1);
        verify(alertForwardingService).sendBusinessCard(anyString(), anyList(), any(), any());
    }

    @Test
    @DisplayName("没有预警项：不发送任何消息")
    void notifyWithoutWarningsDoesNothing() {
        CommentWatch healthy = watch("w1", null);
        healthy.setTokenSetAt(LocalDateTime.now());
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of(healthy));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, false);

        assertThat(result.candidates()).isZero();
        assertThat(result.notified()).isZero();
        verify(alertForwardingService, never()).sendBusinessCard(anyString(), anyList(), any(), any());
    }

    @Test
    @DisplayName("消息体包含标题、条目与处理入口")
    void messageContainsActionHint() {
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of(watch("w1", "EXPIRING")));

        notifier.notifyIfNeeded(null, false);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(alertForwardingService, times(1)).sendBusinessCard(title.capture(), body.capture(), any(), any());
        assertThat(title.getValue()).contains("预警").contains("待处理");
        assertThat(String.join("\n", body.getValue())).contains("建议尽快更新").contains("处理入口");
    }

    @Test
    @DisplayName("账号级令牌失效并入同一条预警，并带「操作直达」链接")
    void includesAccountLevelFailureAndActionLink() {
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of());
        when(credentialService.healthList(any())).thenReturn(List.of(
                new CredentialService.CredentialHealth("cred-1", "owner-1", "xiaohongshu", "主号A",
                        "http://127.0.0.1:18060", "xhs-mcp", true, true, "****en-A", false,
                        "AUTH_INVALID", LocalDateTime.now(), "未授权", null, null)));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.notified()).isEqualTo(1);
        assertThat(String.join("\n", result.items()))
                .contains("账号令牌 AUTH_TOKEN")
                .contains("主号A")
                .contains("重新扫码登录");
        verify(alertForwardingService).sendBusinessCard(anyString(), anyList(), any(), any());
        verify(credentialService).markTokenNotified("cred-1", "AUTH_INVALID");
    }

    @Test
    @DisplayName("账号级预警去重：同状态在提醒窗口内不重复推送")
    void dedupesAccountLevelWarning() {
        when(watchRepository.list(any(), anyInt())).thenReturn(List.of());
        when(credentialService.healthList(any())).thenReturn(List.of(
                new CredentialService.CredentialHealth("cred-1", "owner-1", "xiaohongshu", "主号A",
                        "http://127.0.0.1:18060", "xhs-mcp", true, true, "****en-A", false,
                        "AUTH_INVALID", LocalDateTime.now(), "未授权", "AUTH_INVALID",
                        LocalDateTime.now().minusMinutes(10))));

        TokenWarningNotifier.NotifyResult result = notifier.notifyIfNeeded(null, false);

        assertThat(result.notified()).isZero();
        verify(alertForwardingService, never()).sendBusinessCard(anyString(), anyList(), any(), any());
    }
    private CommentWatch watch(String watchId, String warning) {
        CommentWatch watch = CommentWatch.builder()
                .watchId(watchId)
                .ownerId("owner-1")
                .platform("xiaohongshu")
                .workId("note-" + watchId)
                .enabled(true)
                .autoAnalyze(true)
                .createdAt(LocalDateTime.now().minusDays(9))
                .tokenSetAt(LocalDateTime.now().minusDays(9))
                .build();
        if (warning != null) {
            watch.setTokenState(warning);
            watch.setObservedTtlDays(warning.equals("EXPIRED") ? 5 : null);
        }
        return watch;
    }
}