package com.contentops.comment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语料库前置匹配与关系标签的单元测试（不触发 ONNX 嵌入：走精确匹配/停用分支）。
 */
class IntentCorpusAndRelationTest {

    private IntentCorpusRepository corpusRepository;
    private CommentUserRepository userRepository;
    private CommentProperties properties;

    @BeforeEach
    void setUp() {
        corpusRepository = mock(IntentCorpusRepository.class);
        userRepository = mock(CommentUserRepository.class);
        properties = new CommentProperties();
    }

    @Test
    @DisplayName("语料库：归一化精确匹配直接命中（不调用向量模型）")
    void corpusExactMatchHits() {
        when(corpusRepository.listEnabled(any())).thenReturn(List.of(
                IntentCorpusEntry.builder().corpusId("c1").intent("咨询").phrase("这个在哪里买")
                        .source("SEED").enabled(true).build()));
        IntentCorpusService service = new IntentCorpusService(corpusRepository, properties);

        IntentCorpusService.MatchResult result = service.match("这个在哪里买？", null);

        assertThat(result.matched()).isTrue();
        assertThat(result.intent()).isEqualTo("咨询");
        assertThat(result.matchedBy()).isEqualTo("EXACT");
        assertThat(result.score()).isEqualTo(1.0);
        verify(corpusRepository).incrementHit("c1");
    }

    @Test
    @DisplayName("语料库：关闭开关时直接判定未命中")
    void corpusDisabledSkipsMatch() {
        properties.getIntentRag().setEnabled(false);
        IntentCorpusService service = new IntentCorpusService(corpusRepository, properties);

        IntentCorpusService.MatchResult result = service.match("这个在哪里买", null);

        assertThat(result.matched()).isFalse();
        assertThat(result.score()).isZero();
    }

    @Test
    @DisplayName("语料库：重复说法不重复入库")
    void corpusAddSkipsDuplicate() {
        when(corpusRepository.exists("咨询", "这个在哪里买", null)).thenReturn(true);
        IntentCorpusService service = new IntentCorpusService(corpusRepository, properties);

        assertThat(service.add(null, "咨询", "这个在哪里买")).isNull();
    }

    @Test
    @DisplayName("关系：评论次数达阈值判定为常客")
    void relationRegularByCommentCount() {
        when(userRepository.relationOf(anyString())).thenReturn(null);
        when(userRepository.commentCount(anyString())).thenReturn(5);
        CommentRelationService service = new CommentRelationService(userRepository, properties);

        String relation = service.touchAndResolve("owner-1", "xiaohongshu", "u-1", "桃桃", false);

        assertThat(relation).isEqualTo(CommentRelationService.REGULAR);
        verify(userRepository).upsert(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("关系：显式标签优先（粉丝同步不被常客覆盖）")
    void relationExplicitTagWins() {
        when(userRepository.relationOf(anyString())).thenReturn(CommentRelationService.FAN);
        CommentRelationService service = new CommentRelationService(userRepository, properties);

        String relation = service.resolve("owner-1", "xiaohongshu", "u-1", false);

        assertThat(relation).isEqualTo(CommentRelationService.FAN);
    }

    @Test
    @DisplayName("关系：新增关注通知 → 标记粉丝")
    void relationMarkFollower() {
        CommentRelationService service = new CommentRelationService(userRepository, properties);

        boolean marked = service.markFollower("owner-1", "xiaohongshu", "u-9", "新粉");

        assertThat(marked).isTrue();
        verify(userRepository).setRelation(anyString(), org.mockito.ArgumentMatchers.eq("FAN"),
                org.mockito.ArgumentMatchers.eq("CONNECTIONS"), anyString());
    }

    @Test
    @DisplayName("关系：手工设置关注/好友标签")
    void relationManualTag() {
        CommentRelationService service = new CommentRelationService(userRepository, properties);

        boolean ok = service.setRelation("owner-1", "xiaohongshu", "u-2", "friend", "线下认识");

        assertThat(ok).isTrue();
        verify(userRepository).setRelation(anyString(), org.mockito.ArgumentMatchers.eq("FRIEND"),
                org.mockito.ArgumentMatchers.eq("MANUAL"), anyString());
    }

    @Test
    @DisplayName("关系：概览返回分布与用户列表")
    void relationOverview() {
        when(userRepository.statsByRelation(any())).thenReturn(List.of(Map.of("relation", "FAN", "cnt", 3L)));
        when(userRepository.list(any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("user_key", "xiaohongshu:u-1")));
        CommentRelationService service = new CommentRelationService(userRepository, properties);

        Map<String, Object> overview = service.overview("owner-1", null, 50);

        assertThat(overview).containsKeys("byRelation", "users", "regularThreshold");
        assertThat((List<?>) overview.get("byRelation")).hasSize(1);
    }
}