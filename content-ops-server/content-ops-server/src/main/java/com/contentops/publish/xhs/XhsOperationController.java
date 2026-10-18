package com.contentops.publish.xhs;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.publish.mcp.PlatformPublishController;
import com.contentops.publish.mcp.PlatformPublishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小红书运营动作入口：一次调用完成「商品上架 → 笔记发布（挂车）」。
 */
@RestController
@RequestMapping("/api/v1/publish/xiaohongshu")
@RequiredArgsConstructor
@Tag(name = "小红书运营（上架 + 发布）")
public class XhsOperationController {

    private final XhsOperationService operationService;

    @PostMapping("/operations/run")
    @Operation(summary = "商品上架 → 笔记发布并挂车（商品上架失败则不发布笔记）")
    public AgentResponse<Map<String, Object>> run(@RequestBody OperationRequest request) {
        if (request.getNote() == null || request.getNote().getTitle() == null
                || request.getNote().getTitle().isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "note.title");
        }
        if (request.getNote().getContent() == null || request.getNote().getContent().isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "note.content");
        }
        PlatformPublishService.ProductContent product = null;
        if (request.getProduct() != null) {
            product = new PlatformPublishService.ProductContent(
                    request.getProduct().getTitle(), request.getProduct().getPrice(),
                    request.getProduct().getStock(), request.getProduct().getDescription(),
                    request.getProduct().getImages(), request.getProduct().getSku(),
                    request.getProduct().getCategory());
        }
        XhsOperationService.OperationResult result = operationService.run(
                request.getPlatform(), request.getNote().getTitle(), request.getNote().getContent(),
                request.getNote().getImages(), request.getNote().getProducts(), product,
                request.isDryRun());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", result.ok());
        data.put("platform", result.platform());
        data.put("dryRun", request.isDryRun());
        data.put("steps", result.steps());
        return AgentResponse.success("publish", data);
    }

    @Data
    public static class OperationRequest {
        /** 平台键，默认 xiaohongshu；联调时可用 stub */
        private String platform = XhsOperationService.DEFAULT_PLATFORM;
        private NoteRequest note;
        private PlatformPublishController.ProductRequest product;
        private boolean dryRun;
    }

    @Data
    public static class NoteRequest {
        private String title;
        private String content;
        private List<String> images;
        /** 额外挂车商品（关键词或 ID） */
        private List<String> products;
    }
}
