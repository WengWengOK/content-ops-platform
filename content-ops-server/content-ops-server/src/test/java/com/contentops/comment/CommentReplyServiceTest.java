package com.contentops.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回复服务测试：真实发送 / 模拟发送 / 失败不误标 SENT / xsec_token 解析。
 */
class CommentReplyServiceTest {

    private CommentRepository repository;
    private XhsCommentApiClient apiClient;
    private CommentWatchRepository watchRepository;
    private CommentProperties properties;
    private CommentReplyService service;

    @BeforeEach
    void setUp() {
        repository = mock(CommentRepository.class);
        apiClient = mock(XhsCommentApiClient.class);
        watchRepository = mock(CommentWatchRepository.class);
        properties = new CommentProperties();
        service = new CommentReplyService(repository, new ObjectMapper(), mock(ChatModel.class),
                apiClient, watchRepository, properties);
    }

    @Test
    @DisplayName("未配置真实接口时：模拟发送，状态置 SENT")
    void send_withoutRealApi_simulates() {
        when(repository.findById("c1")).thenReturn(Optional.of(comment("c1", "APPROVED")));
        when(apiClient.isReplyConfigured()).thenReturn(false);
        when(apiClient.replyStatusHint()).thenReturn("真实接口未启用");

        CommentReplyService.SendResult result = service.send("c1", null);

        assertThat(result.sendMode()).isEqualTo("simulated");
        assertThat(result.message()).contains("模拟发送");
        verify(repository).updateReply("c1", "谢谢支持～", "SENT");
        verify(apiClient, never()).reply(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("配置了真实接口时：调用桥真实回复，xsec_token 取自该作品的监控项")
    void send_withRealApi_usesWatchToken() {
        Comment comment = comment("c1", "APPROVED");
        when(repository.findById("c1")).thenReturn(Optional.of(comment));
        when(apiClient.isReplyConfigured()).thenReturn(true);
        when(watchRepository.findByWork("xiaohongshu", "note-9", "owner-1"))
                .thenReturn(Optional.of(CommentWatch.builder()
                        .watchId("w1").platform("xiaohongshu").workId("note-9")
                        .xsecToken("xs-from-watch").autoAnalyze(true).enabled(true).build()));
        when(apiClient.reply(eq("note-9"), eq("xs-from-watch"), eq("c-raw-1"), eq("谢谢支持～")))
                .thenReturn(new XhsCommentApiClient.ReplyResult(true, "回复成功"));

        CommentReplyService.SendResult result = service.send("c1", null);

        assertThat(result.sendMode()).isEqualTo("real");
        assertThat(result.message()).contains("回复成功");
        verify(repository).updateReply("c1", "谢谢支持～", "SENT");
    }

    @Test
    @DisplayName("真实回复失败：保持 APPROVED，不误标 SENT")
    void send_realFailure_keepsApproved() {
        when(repository.findById("c1")).thenReturn(Optional.of(comment("c1", "APPROVED")));
        when(apiClient.isReplyConfigured()).thenReturn(true);
        when(watchRepository.findByWork(eq("xiaohongshu"), eq("note-9"), eq("owner-1")))
                .thenReturn(Optional.of(CommentWatch.builder()
                        .watchId("w1").platform("xiaohongshu").workId("note-9")
                        .xsecToken("xs").autoAnalyze(true).enabled(true).build()));
        when(apiClient.reply(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new XhsCommentApiClient.ReplyResult(false, "回复过于频繁"));

        RuntimeException ex = catchThrowableOfType(() -> service.send("c1", null), RuntimeException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("真实回复失败");
        verify(repository, never()).updateReply(anyString(), anyString(), eq("SENT"));
    }

    @Test
    @DisplayName("缺少 xsec_token：抛可读错误（映射为缺少必填参数）")
    void send_missingXsecToken_throws() {
        when(repository.findById("c1")).thenReturn(Optional.of(comment("c1", "APPROVED")));
        when(apiClient.isReplyConfigured()).thenReturn(true);
        when(watchRepository.findByWork(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.reply(anyString(), any(), anyString(), anyString()))
                .thenThrow(new CommentSourceException("COMMENT_XSEC_TOKEN_REQUIRED",
                        "真实回复需要 xsec_token：请在「监控作品」里补该笔记的 xsec_token"));

        RuntimeException ex = catchThrowableOfType(() -> service.send("c1", null), RuntimeException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("xsec_token");
    }

    @Test
    @DisplayName("只有 APPROVED 状态才允许发送")
    void send_wrongStatus_throws() {
        when(repository.findById("c1")).thenReturn(Optional.of(comment("c1", "DRAFT")));

        RuntimeException ex = catchThrowableOfType(() -> service.send("c1", null), RuntimeException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("请先审核通过");
    }

    private Comment comment(String id, String status) {
        return Comment.builder()
                .commentId(id)
                .platformCommentId("c-raw-1")
                .ownerId("owner-1")
                .platform("xiaohongshu")
                .workId("note-9")
                .author("桃桃")
                .content("求同款链接！")
                .aiReply("谢谢支持～")
                .replyStatus(status)
                .collectedAt(LocalDateTime.now())
                .build();
    }
}