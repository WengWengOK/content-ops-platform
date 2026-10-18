package com.contentops.publish.xhs;

import com.contentops.common.audit.AuditService;
import com.contentops.publish.mcp.PlatformPublishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小红书「商品上架 → 笔记发布（挂车）」编排测试。
 */
class XhsOperationServiceTest {

    private PlatformPublishService publishService;
    private AuditService auditService;
    private XhsOperationService service;

    @BeforeEach
    void setUp() {
        publishService = mock(PlatformPublishService.class);
        auditService = mock(AuditService.class);
        service = new XhsOperationService(publishService, auditService);
    }

    private PlatformPublishService.ProductContent product() {
        return new PlatformPublishService.ProductContent("保湿面膜 10 片装", "59.90", 100,
                "补水保湿", List.of("https://cdn/p1.jpg"), "SKU-001", "美妆护肤");
    }

    private PlatformPublishService.PublishOutcome ok(String channel, String tool, String detail) {
        return new PlatformPublishService.PublishOutcome(true, channel, tool, detail, 12, null);
    }

    @Test
    @DisplayName("先上架商品，再发布笔记并自动挂车")
    void listsProductThenPublishesNoteWithProduct() {
        when(publishService.publishProduct(eq("xiaohongshu"), any(), eq(false)))
                .thenReturn(ok("mcp", "publish_product", "商品ID：p-1"));
        when(publishService.publish(eq("xiaohongshu"), any(), eq(false)))
                .thenReturn(ok("mcp", "publish_content", "笔记已发布"));

        XhsOperationService.OperationResult result = service.run("xiaohongshu",
                "秋冬补水面膜实测", "正文", List.of("https://cdn/n1.jpg"),
                List.of("店铺爆款"), product(), false);

        assertThat(result.ok()).isTrue();
        assertThat(result.steps()).extracting(XhsOperationService.StepResult::step)
                .containsExactly("product-listing", "note-publish");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformPublishService.PublishContent> noteCaptor =
                ArgumentCaptor.forClass(PlatformPublishService.PublishContent.class);
        verify(publishService).publish(eq("xiaohongshu"), noteCaptor.capture(), eq(false));
        assertThat(noteCaptor.getValue().products())
                .containsExactly("店铺爆款", "保湿面膜 10 片装");
        verify(auditService).record(eq("XHS_OPERATION"), eq("platform"), eq("xiaohongshu"), anyString());
    }

    @Test
    @DisplayName("商品上架失败：不发布笔记（避免无货可挂）")
    void abortsWhenProductFails() {
        when(publishService.publishProduct(eq("xiaohongshu"), any(), eq(false)))
                .thenReturn(new PlatformPublishService.PublishOutcome(false, "rpa", "runApp",
                        "千帆登录失效", 30, null));

        XhsOperationService.OperationResult result = service.run("xiaohongshu",
                "标题", "正文", List.of("img"), List.of(), product(), false);

        assertThat(result.ok()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).step()).isEqualTo("note-publish");
        assertThat(result.steps().get(1).ok()).isFalse();
        assertThat(result.steps().get(1).detail()).contains("已停止发布笔记");
        verify(publishService, never()).publish(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("演练模式：两步都按计划通过，不真实操作")
    void dryRunPlansBothSteps() {
        when(publishService.publishProduct(eq("xiaohongshu"), any(), eq(true)))
                .thenReturn(new PlatformPublishService.PublishOutcome(false, "mcp-dry-run", "publish_product",
                        "演练模式未真实上架，将使用的参数：{title=保湿面膜}", 1, null));
        when(publishService.publish(eq("xiaohongshu"), any(), eq(true)))
                .thenReturn(new PlatformPublishService.PublishOutcome(false, "mcp-dry-run", "publish_content",
                        "演练模式未真实上传，将使用的参数：{products=[保湿面膜 10 片装]}", 1, null));

        XhsOperationService.OperationResult result = service.run("xiaohongshu",
                "标题", "正文", List.of("img"), List.of(), product(), true);

        assertThat(result.ok()).isTrue();
        assertThat(result.steps()).allMatch(XhsOperationService.StepResult::planned);
    }

    @Test
    @DisplayName("只发笔记（没有商品）：跳过上架步骤")
    void noteOnly() {
        when(publishService.publish(eq("xiaohongshu"), any(), eq(false)))
                .thenReturn(ok("mcp", "publish_content", "已发布"));

        XhsOperationService.OperationResult result = service.run("xiaohongshu",
                "标题", "正文", List.of("img"), List.of("已有商品"), null, false);

        assertThat(result.ok()).isTrue();
        assertThat(result.steps()).extracting(XhsOperationService.StepResult::step)
                .containsExactly("note-publish");
        verify(publishService, never()).publishProduct(anyString(), any(), anyBoolean());
    }
}
