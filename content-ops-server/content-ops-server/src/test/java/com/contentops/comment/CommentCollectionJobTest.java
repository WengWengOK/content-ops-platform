package com.contentops.comment;

import com.contentops.common.credential.CredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集任务测试：去重、自动分析预算、失败记录、空监控列表。
 */
class CommentCollectionJobTest {

    private CommentCollector collector;
    private CommentRepository commentRepository;
    private CommentWatchRepository watchRepository;
    private CommentJobRunRepository jobRunRepository;
    private CommentAnalysisService analysisService;
    private CommentProperties properties;
    private CredentialService credentialService;
    private CommentCollectionJob job;

    @BeforeEach
    void setUp() {
        collector = mock(CommentCollector.class);
        commentRepository = mock(CommentRepository.class);
        watchRepository = mock(CommentWatchRepository.class);
        jobRunRepository = mock(CommentJobRunRepository.class);
        analysisService = mock(CommentAnalysisService.class);
        properties = new CommentProperties();
        credentialService = mock(CredentialService.class);
        when(collector.collectFromNotifications(any(), any(), anyInt(), any())).thenReturn(
                new CommentCollector.NotificationCollectionResult("none", "未配置", "mentions", 0,
                        List.of(), 0, Map.of()));
        job = new CommentCollectionJob(collector, commentRepository, watchRepository,
                jobRunRepository, analysisService, properties, credentialService);
    }

    @Test
    @DisplayName("单作品采集：跳过已存在评论，按预算自动分析并更新监控状态")
    void runForWatch_dedupesAndAnalyzesWithinBudget() {
        properties.setAutoAnalyze(true);
        properties.setAutoAnalyzeLimit(2);
        CommentWatch watch = watch("w-1", true);

        when(collector.collect("xiaohongshu", "note-1", "owner-1", null, null)).thenReturn(
                new CommentCollector.CollectionResult("xiaohongshu", "note-1", "api", null,
                        List.of(comment("c-1"), comment("c-2"), comment("c-3"))));
        when(commentRepository.exists("c-1")).thenReturn(true);
        when(commentRepository.exists("c-2")).thenReturn(false, true);
        when(commentRepository.exists("c-3")).thenReturn(false, true);
        when(analysisService.analyze(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setIntent("咨询");
            c.setSentiment("NEUTRAL");
            c.setAiSummary("摘要");
            c.setAiReply("回复草稿");
            return c;
        });

        CommentCollectionJob.WatchRunResult result = job.runForWatch(watch, 2);

        assertThat(result.source()).isEqualTo("api");
        assertThat(result.collected()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.analyzed()).isEqualTo(2);
        assertThat(result.error()).isNull();
        verify(commentRepository, times(2)).insert(any(Comment.class));
        verify(commentRepository, times(2)).updateAnalysisAndStatus(anyString(), eq("咨询"),
                eq("NEUTRAL"), eq("摘要"), eq("回复草稿"));
        verify(watchRepository).updateAfterRun("w-1", 2, 2, "api", null);
    }

    @Test
    @DisplayName("自动分析关闭时不调用模型")
    void runForWatch_autoAnalyzeDisabled_skipsModel() {
        properties.setAutoAnalyze(true);
        CommentWatch watch = watch("w-1", false);
        when(collector.collect(anyString(), anyString(), any(), any(), any())).thenReturn(
                new CommentCollector.CollectionResult("xiaohongshu", "note-1", "api", null,
                        List.of(comment("c-9"))));
        when(commentRepository.exists("c-9")).thenReturn(false, true);

        CommentCollectionJob.WatchRunResult result = job.runForWatch(watch, 5);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.analyzed()).isZero();
        verify(analysisService, never()).analyze(any(Comment.class));
    }

    @Test
    @DisplayName("采集异常时记录错误，不影响任务继续")
    void runForWatch_collectError_recordsError() {
        CommentWatch watch = watch("w-2", true);
        when(collector.collect(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new CommentSourceException("COMMENT_SOURCE_UNAUTHORIZED", "鉴权失败"));

        CommentCollectionJob.WatchRunResult result = job.runForWatch(watch, 5);

        assertThat(result.error()).contains("鉴权失败");
        assertThat(result.inserted()).isZero();
        verify(watchRepository).updateAfterRun(eq("w-2"), eq(0), eq(0), isNull(), anyString());
    }

    @Test
    @DisplayName("没有到期监控项时，任务记录为零并仍然落库")
    void runOnce_noWatches_writesEmptyRun() {
        when(watchRepository.listDue(anyInt(), anyInt())).thenReturn(List.of());

        CommentJobRun run = job.runOnce("schedule");

        assertThat(run.getWorksScanned()).isZero();
        assertThat(run.getCommentsNew()).isZero();
        assertThat(run.getTriggerType()).isEqualTo("schedule");
        verify(jobRunRepository).insert(any(CommentJobRun.class));
    }

    private CommentWatch watch(String watchId, boolean autoAnalyze) {
        return CommentWatch.builder()
                .watchId(watchId)
                .ownerId("owner-1")
                .platform("xiaohongshu")
                .workId("note-1")
                .autoAnalyze(autoAnalyze)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Comment comment(String id) {
        return Comment.builder()
                .commentId(id)
                .ownerId("owner-1")
                .platform("xiaohongshu")
                .workId("note-1")
                .author("用户")
                .content("评论内容")
                .likes(1)
                .replyStatus("NONE")
                .collectedAt(LocalDateTime.now())
                .build();
    }
}