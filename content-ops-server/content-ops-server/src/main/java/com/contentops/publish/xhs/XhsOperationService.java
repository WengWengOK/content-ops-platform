package com.contentops.publish.xhs;

import com.contentops.common.audit.AuditService;
import com.contentops.publish.mcp.PlatformPublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 小红书运营动作编排：**商品上架 → 笔记发布（挂车）**。
 *
 * <p>为什么是这个顺序：
 * <ol>
 *   <li>小红书笔记要挂车，商品必须先在千帆上架（{@code xiaohongshu-mcp} 的 {@code publish_content.products}
 *       是按关键词/商品 ID 搜索已有商品，找不到就挂不上）；</li>
 *   <li>商品上架失败时**不发布笔记**，避免发出一条无货可挂的种草笔记；</li>
 *   <li>上架成功则自动把商品标题加进挂车列表，发布时一并带上。</li>
 * </ol>
 *
 * <p>两步都走 {@link PlatformPublishService} 的通道模型（原生 MCP / 影刀 RPA / 模拟），
 * 所以「发布」和「上架」可以分别用不同通道，互不绑架。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XhsOperationService {

    public static final String DEFAULT_PLATFORM = "xiaohongshu";

    /** 单步结果 */
    public record StepResult(String step, boolean ok, boolean planned, String channel, String tool,
                             String detail, long elapsedMs) {
    }

    /** 整体结果 */
    public record OperationResult(boolean ok, String platform, List<StepResult> steps) {
    }

    private final PlatformPublishService publishService;
    private final AuditService auditService;

    /**
     * 执行一次「上架 + 发布」。
     *
     * @param platform     平台键（默认 xiaohongshu；测试可用 stub 通道）
     * @param noteTitle    笔记标题
     * @param noteContent  笔记正文
     * @param noteImages   笔记图片
     * @param bindProducts 额外挂车商品（关键词或商品 ID）
     * @param product      要上架的商品，可为 null（只发布笔记）
     * @param dryRun       演练（只回显参数，不真实操作）
     */
    public OperationResult run(String platform, String noteTitle, String noteContent, List<String> noteImages,
                               List<String> bindProducts, PlatformPublishService.ProductContent product,
                               boolean dryRun) {
        String key = platform == null || platform.isBlank()
                ? DEFAULT_PLATFORM : platform.trim().toLowerCase();
        List<StepResult> steps = new ArrayList<>();
        List<String> products = new ArrayList<>(bindProducts == null ? List.of() : bindProducts);
        boolean ok = true;

        if (product != null) {
            PlatformPublishService.PublishOutcome listing =
                    publishService.publishProduct(key, product, dryRun);
            boolean planned = isPlanned(listing);
            steps.add(new StepResult("product-listing", listing.ok() || planned, planned,
                    listing.channel(), listing.tool(), listing.detail(), listing.elapsedMs()));
            if (!listing.ok() && !planned) {
                ok = false;
                steps.add(new StepResult("note-publish", false, false, null, null,
                        "商品上架失败，已停止发布笔记（避免发出无货可挂的笔记）", 0));
            } else if (product.title() != null && !product.title().isBlank()
                    && !products.contains(product.title())) {
                // 上架成功后自动挂车
                products.add(product.title());
            }
        }

        if (ok) {
            PlatformPublishService.PublishOutcome note = publishService.publish(key,
                    new PlatformPublishService.PublishContent(noteTitle, noteContent, noteImages,
                            null, null, null, products, null), dryRun);
            boolean planned = isPlanned(note);
            steps.add(new StepResult("note-publish", note.ok() || planned, planned,
                    note.channel(), note.tool(), note.detail(), note.elapsedMs()));
            ok = note.ok() || planned;
        }

        auditService.record("XHS_OPERATION", "platform", key,
                "商品上架→笔记发布：" + (ok ? "成功" : "失败") + "，步骤数 " + steps.size()
                        + (dryRun ? "（演练）" : ""));
        log.info("[XhsOperation] platform={}, ok={}, dryRun={}, steps={}", key, ok, dryRun, steps.size());
        return new OperationResult(ok, key, List.copyOf(steps));
    }

    private boolean isPlanned(PlatformPublishService.PublishOutcome outcome) {
        return outcome.channel() != null && outcome.channel().endsWith("dry-run");
    }
}
