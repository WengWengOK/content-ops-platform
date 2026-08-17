package com.contentops.common.multimodal;

import com.contentops.common.metrics.TokenEstimator;
import com.contentops.common.metrics.TokenMetricsService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 视觉理解服务。
 *
 * <p>基于 LangChain4j 的 {@link ChatModel}（视觉模型，如 GPT-4o、GPT-4V）实现图片视觉理解能力，
 * 与 {@link MultimodalRequestBuilder} 配合构建多模态请求，并通过 {@link TokenMetricsService}
 * 记录 token 消耗与调用指标。
 *
 * <h3>支持的能力</h3>
 * <ul>
 *   <li><b>图片描述生成</b>（{@link #describeImage}）：生成图片的详细文字描述</li>
 *   <li><b>图片内容问答</b>（{@link #answerQuestion}）：针对图片内容回答自然语言提问</li>
 *   <li><b>图片文字提取 / OCR</b>（{@link #extractText}）：通过 LLM Vision 提取图片中的文字</li>
 *   <li><b>图片分类与标签</b>（{@link #classifyAndTag}）：输出图片类别与标签集合</li>
 *   <li><b>图片相似度比较</b>（{@link #compareImages}）：对比两张图片的视觉相似度</li>
 * </ul>
 *
 * <h3>批量处理</h3>
 * <p>提供 {@link #batchDescribe}、{@link #batchExtractText} 等批量接口，支持一次处理多张图片。
 * 批量处理采用串行调用 + 容错降级策略：单张图片处理失败不影响其余图片，失败项以错误描述占位。
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>多模态能力未启用（{@code contentops.multimodal.enabled=false}）时，
 *       所有方法返回降级提示文本而非抛出异常</li>
 *   <li>视觉模型调用异常时，捕获并返回错误描述，同时通过 {@link TokenMetricsService}
 *       记录失败调用，不向上传播异常以保证批量任务连续性</li>
 *   <li>批量任务中单条失败以错误占位结果返回</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 单图描述
 * String desc = visionAnalysisService.describeImage("https://example.com/a.png", "详细描述图片内容");
 *
 * // 图片问答
 * String answer = visionAnalysisService.answerQuestion(imageUrl, "图中人物穿着什么颜色的衣服？");
 *
 * // 批量 OCR
 * List<VisionResult> results = visionAnalysisService.batchExtractText(List.of(url1, url2, url3));
 * }</pre>
 *
 * @see MultimodalRequestBuilder
 * @see TokenMetricsService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisionAnalysisService {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final MultimodalRequestBuilder requestBuilder;
    private final TokenMetricsService tokenMetricsService;
    private final VisionProperties properties;

    /** 视觉理解的 Agent 阶段标签，用于指标记录 */
    private static final String VISION_STAGE = "vision-analysis";

    // ════════════════════════════════════════════════════════════════
    //  系统提示词
    // ════════════════════════════════════════════════════════════════

    /** 图片描述系统提示词 */
    private static final String SYSTEM_DESCRIBE = """
            你是一个专业的视觉理解助手，擅长用准确、生动的语言描述图片内容。
            描述要求：
            1. 先概述图片的整体场景与主体
            2. 再描述细节：色彩、构图、人物、物体、文字、氛围
            3. 必要时给出合理的推断（需标注"推断"）
            4. 语言简洁，逻辑清晰""";

    /** 图片问答系统提示词 */
    private static final String SYSTEM_QA = """
            你是一个专业的图片问答助手。请根据图片内容准确回答用户提问。
            回答要求：
            1. 仅基于图片可见内容作答，不臆测
            2. 若图片中无法找到答案，明确说明"图片中无法确定"
            3. 回答简洁、准确、有条理""";

    /** OCR 系统提示词 */
    private static final String SYSTEM_OCR = """
            你是一个专业的 OCR 文字识别助手。请准确提取图片中的所有文字内容。
            要求：
            1. 按原文逐字提取，保留原文的标点、换行与排版
            2. 区分标题、正文、标注等不同层级
            3. 表格内容以 Markdown 表格形式输出
            4. 若图片中无文字，返回"[无文字内容]"
            5. 对于模糊或难以辨认的字，用 [?] 标注""";

    /** 图片分类与标签系统提示词 */
    private static final String SYSTEM_CLASSIFY = """
            你是一个专业的图片分类与标签生成助手。请对图片进行分类并生成标签。
            输出格式（严格遵循 JSON）：
            {
              "category": "主类别（如 自然风景 / 人物 / 美食 / 产品 / 图表 / 截图 等）",
              "subcategory": "子类别",
              "tags": ["标签1", "标签2", "标签3"],
              "confidence": 0.0到1.0的置信度
            }
            要求标签具体、可检索，数量 3-8 个。""";

    /** 图片相似度比较系统提示词 */
    private static final String SYSTEM_COMPARE = """
            你是一个专业的图片相似度比较助手。请从多个维度对比两张图片的相似程度。
            输出格式（严格遵循 JSON）：
            {
              "overallScore": 0到100的整体相似度,
              "dimensions": {
                "subject": 主体相似度0-100,
                "color": 色彩相似度0-100,
                "composition": 构图相似度0-100,
                "style": 风格相似度0-100
              },
              "differences": ["主要差异点1", "主要差异点2"],
              "summary": "一句话总结对比结论"
            }""";

    // ════════════════════════════════════════════════════════════════
    //  单图能力
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成图片描述。
     *
     * @param imageUrl 图片 URL
     * @param hint     描述提示（如"侧重描述色彩"，为空时使用默认提示）
     * @return 图片描述文本；多模态未启用时返回降级提示
     */
    public String describeImage(String imageUrl, String hint) {
        if (!isAvailable()) {
            return degrade("图片描述", imageUrl);
        }
        String prompt = (hint == null || hint.isBlank())
                ? "请详细描述这张图片的内容。"
                : "请详细描述这张图片的内容。" + hint;
        return invokeVision(SYSTEM_DESCRIBE,
                requestBuilder.buildUrlRequest(prompt, imageUrl),
                "describeImage");
    }

    /**
     * 生成图片描述（使用默认提示）。
     *
     * @param imageUrl 图片 URL
     * @return 图片描述文本
     */
    public String describeImage(String imageUrl) {
        return describeImage(imageUrl, null);
    }

    /**
     * 针对图片内容回答提问。
     *
     * @param imageUrl 图片 URL
     * @param question 自然语言提问
     * @return 回答文本
     */
    public String answerQuestion(String imageUrl, String question) {
        if (!isAvailable()) {
            return degrade("图片问答", imageUrl);
        }
        String prompt = (question == null || question.isBlank())
                ? "请描述这张图片。"
                : question;
        return invokeVision(SYSTEM_QA,
                requestBuilder.buildUrlRequest(prompt, imageUrl),
                "answerQuestion");
    }

    /**
     * 提取图片中的文字（OCR）。
     *
     * <p>通过 LLM Vision 模型识别图片中的文字，适用于截图、文档扫描件、海报等。
     *
     * @param imageUrl 图片 URL
     * @return 提取的文字内容（Markdown 格式）；无文字时返回 "[无文字内容]"
     */
    public String extractText(String imageUrl) {
        if (!isAvailable()) {
            return degrade("OCR 文字提取", imageUrl);
        }
        return invokeVision(SYSTEM_OCR,
                requestBuilder.buildUrlRequest("请提取这张图片中的所有文字内容。", imageUrl),
                "extractText");
    }

    /**
     * 对图片进行分类并生成标签。
     *
     * @param imageUrl 图片 URL
     * @return JSON 格式的分类与标签结果
     */
    public String classifyAndTag(String imageUrl) {
        if (!isAvailable()) {
            return degrade("图片分类", imageUrl);
        }
        return invokeVision(SYSTEM_CLASSIFY,
                requestBuilder.buildUrlRequest("请对这张图片进行分类并生成标签。", imageUrl),
                "classifyAndTag");
    }

    /**
     * 比较两张图片的相似度。
     *
     * @param imageUrl1 第一张图片 URL
     * @param imageUrl2 第二张图片 URL
     * @return JSON 格式的相似度对比结果
     */
    public String compareImages(String imageUrl1, String imageUrl2) {
        if (!isAvailable()) {
            return degrade("图片相似度比较", imageUrl1 + " vs " + imageUrl2);
        }
        MultimodalRequestBuilder.MultimodalRequest request = requestBuilder.newRequest()
                .addText("请对比以下两张图片的相似度，第一张为图1，第二张为图2。")
                .addImageUrl(imageUrl1)
                .addImageUrl(imageUrl2)
                .build();
        return invokeVision(SYSTEM_COMPARE, request, "compareImages");
    }

    // ════════════════════════════════════════════════════════════════
    //  Base64 / 文件路径重载
    // ════════════════════════════════════════════════════════════════

    /**
     * 对 Base64 编码的图片生成描述。
     *
     * @param base64Data Base64 数据
     * @param mimeType   MIME 类型
     * @param hint       描述提示
     * @return 图片描述文本
     */
    public String describeImageBase64(String base64Data, String mimeType, String hint) {
        if (!isAvailable()) {
            return degrade("图片描述", "[base64图片]");
        }
        String prompt = (hint == null || hint.isBlank())
                ? "请详细描述这张图片的内容。"
                : "请详细描述这张图片的内容。" + hint;
        return invokeVision(SYSTEM_DESCRIBE,
                requestBuilder.buildBase64Request(prompt, base64Data, mimeType),
                "describeImageBase64");
    }

    /**
     * 对本地图片文件生成描述。
     *
     * @param filePath 本地图片文件路径
     * @param hint     描述提示
     * @return 图片描述文本
     */
    public String describeImageFile(String filePath, String hint) {
        if (!isAvailable()) {
            return degrade("图片描述", filePath);
        }
        String prompt = (hint == null || hint.isBlank())
                ? "请详细描述这张图片的内容。"
                : "请详细描述这张图片的内容。" + hint;
        return invokeVision(SYSTEM_DESCRIBE,
                requestBuilder.buildFileRequest(prompt, filePath),
                "describeImageFile");
    }

    // ════════════════════════════════════════════════════════════════
    //  批量处理
    // ════════════════════════════════════════════════════════════════

    /**
     * 批量生成图片描述。
     *
     * <p>逐张串行处理，单张失败以错误占位结果返回，不中断整体流程。
     *
     * @param imageUrls 图片 URL 列表
     * @return 每张图片的视觉理解结果列表（顺序与输入一致）
     */
    public List<VisionResult> batchDescribe(List<String> imageUrls) {
        return batchProcess(imageUrls, this::describeImage, "describeImage");
    }

    /**
     * 批量提取图片文字（OCR）。
     *
     * @param imageUrls 图片 URL 列表
     * @return 每张图片的 OCR 结果列表
     */
    public List<VisionResult> batchExtractText(List<String> imageUrls) {
        return batchProcess(imageUrls, this::extractText, "extractText");
    }

    /**
     * 批量图片分类与标签生成。
     *
     * @param imageUrls 图片 URL 列表
     * @return 每张图片的分类标签结果列表
     */
    public List<VisionResult> batchClassifyAndTag(List<String> imageUrls) {
        return batchProcess(imageUrls, this::classifyAndTag, "classifyAndTag");
    }

    /**
     * 通用批量处理框架。
     *
     * @param imageUrls 图片 URL 列表
     * @param processor 单图处理函数
     * @param operation 操作名称（用于日志与指标）
     * @return 视觉理解结果列表
     */
    private List<VisionResult> batchProcess(List<String> imageUrls,
                                             java.util.function.Function<String, String> processor,
                                             String operation) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return Collections.emptyList();
        }
        List<VisionResult> results = new ArrayList<>(imageUrls.size());
        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            Instant start = Instant.now();
            VisionResult result;
            try {
                String content = processor.apply(imageUrl);
                result = VisionResult.builder()
                        .imageUrl(imageUrl)
                        .content(content)
                        .success(true)
                        .index(i)
                        .elapsedMs(Duration.between(start, Instant.now()).toMillis())
                        .build();
            } catch (Exception e) {
                log.error("[Vision] 批量 {} 第 {} 张图片处理失败: {}", operation, i + 1, imageUrl, e);
                result = VisionResult.builder()
                        .imageUrl(imageUrl)
                        .content("[处理失败] " + e.getMessage())
                        .success(false)
                        .index(i)
                        .elapsedMs(Duration.between(start, Instant.now()).toMillis())
                        .errorMessage(e.getMessage())
                        .build();
            }
            results.add(result);
        }
        log.info("[Vision] 批量 {} 完成, 共 {} 张, 成功 {} 张, 失败 {} 张",
                operation, results.size(),
                results.stream().filter(VisionResult::isSuccess).count(),
                results.stream().filter(r -> !r.isSuccess()).count());
        return results;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部执行与降级
    // ════════════════════════════════════════════════════════════════

    /**
     * 判断视觉理解服务是否可用（多模态启用且 ChatModel 已注入）。
     *
     * @return true 表示可用
     */
    public boolean isAvailable() {
        return requestBuilder.isEnabled() && chatModelProvider.getIfAvailable() != null;
    }

    /**
     * 执行一次视觉理解调用，并记录指标。
     *
     * @param systemPrompt 系统提示词
     * @param request      多模态请求
     * @param operation    操作名称（用于日志）
     * @return 模型返回文本；调用异常时返回错误描述
     */
    private String invokeVision(String systemPrompt,
                                 MultimodalRequestBuilder.MultimodalRequest request,
                                 String operation) {
        Instant start = Instant.now();
        boolean success = false;
        String responseText = "";
        try {
            ChatModel model = chatModelProvider.getIfAvailable();
            if (model == null) {
                log.warn("[Vision] ChatModel 不可用, 视觉理解降级");
                responseText = "[视觉理解不可用] ChatModel 未配置";
                return responseText;
            }
            UserMessage userMessage = request.toUserMessage();
            SystemMessage systemMessage = SystemMessage.from(systemPrompt);
            var chatResponse = model.chat(systemMessage, userMessage);
            AiMessage aiMessage = chatResponse.aiMessage();
            responseText = aiMessage == null ? "" : aiMessage.text();
            if (responseText == null) {
                responseText = "";
            }
            success = true;
            return responseText;
        } catch (Exception e) {
            log.error("[Vision] {} 调用视觉模型失败", operation, e);
            responseText = "[视觉理解失败] " + e.getMessage();
            return responseText;
        } finally {
            Duration duration = Duration.between(start, Instant.now());
            recordVisionMetrics(request, responseText, success, duration, operation);
            log.debug("[Vision] {} 完成, 耗时={}ms, success={}", operation, duration.toMillis(), success);
        }
    }

    /**
     * 记录视觉理解调用的指标到 TokenMetricsService。
     */
    private void recordVisionMetrics(MultimodalRequestBuilder.MultimodalRequest request,
                                      String responseText, boolean success,
                                      Duration duration, String operation) {
        try {
            int inputTokens = TokenEstimator.estimate(request.getText())
                    + request.getImageCount() * properties.getImageTokenCost();
            int outputTokens = TokenEstimator.estimate(responseText);
            String stageTag = VISION_STAGE + ":" + operation;
            tokenMetricsService.recordTokenUsage("vision", stageTag, inputTokens, outputTokens);
            tokenMetricsService.recordAgentCall(stageTag, success);
            tokenMetricsService.recordAgentDuration(stageTag, duration);
        } catch (Exception e) {
            log.warn("[Vision] 记录指标失败: {}", e.getMessage());
        }
    }

    /**
     * 多模态不可用时的降级返回。
     *
     * @param capability 能力名称
     * @param imageRef   图片引用（URL 或路径）
     * @return 降级提示文本
     */
    private String degrade(String capability, String imageRef) {
        log.warn("[Vision] 多模态能力未启用, {} 降级处理, image={}", capability, imageRef);
        return "[" + capability + "不可用] 多模态能力未启用或视觉模型未配置。"
                + "请在 application.yml 中设置 contentops.multimodal.enabled=true 并配置视觉模型。";
    }

    // ════════════════════════════════════════════════════════════════
    //  结果 DTO 与配置
    // ════════════════════════════════════════════════════════════════

    /**
     * 视觉理解结果 DTO。
     *
     * <p>用于批量处理时返回单张图片的处理结果，包含文本内容、成功标志、耗时等信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VisionResult {

        /** 图片 URL 或路径 */
        private String imageUrl;

        /** 模型返回的文本内容（描述 / 回答 / OCR 文字 / 分类标签 / 对比结果） */
        private String content;

        /** 是否处理成功 */
        private boolean success;

        /** 在批量结果中的索引（从 0 开始） */
        private int index;

        /** 处理耗时（毫秒） */
        private long elapsedMs;

        /** 失败时的错误信息（成功时为 null） */
        private String errorMessage;
    }

    /**
     * 视觉理解服务配置属性。
     *
     * <p>通过 {@code contentops.vision.*} 在 application.yml 中绑定。
     *
     * <h3>配置示例</h3>
     * <pre>{@code
     * contentops:
     *   vision:
     *     image-token-cost: 85
     *     batch-concurrency: 1
     *     default-language: zh
     * }</pre>
     */
    @Data
    @org.springframework.stereotype.Component
    @ConfigurationProperties(prefix = "contentops.vision")
    public static class VisionProperties {

        /** 单张图片预估的输入 token 成本（用于指标估算，OpenAI Vision 每张图约 85-765 tokens） */
        private int imageTokenCost = 85;

        /** 批量处理并发度（1 为串行，建议保持 1 以避免触发速率限制） */
        private int batchConcurrency = 1;

        /** OCR 与描述的默认输出语言（zh / en） */
        private String defaultLanguage = "zh";
    }
}
