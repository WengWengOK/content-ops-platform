package com.contentops.common.multimodal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 多模态请求构建器。
 *
 * <p>用于构建包含文本与图片的多模态请求，生成符合 OpenAI Vision API 格式的消息结构，
 * 并可转换为 LangChain4j 的 {@link UserMessage} 供视觉模型（如 GPT-4V、GPT-4o）消费。
 *
 * <h3>支持的图片输入模式</h3>
 * <ul>
 *   <li><b>{@link ImageInputMode#IMAGE_URL}</b>：通过 URL 引用图片，适用于公网可访问的图片资源</li>
 *   <li><b>{@link ImageInputMode#IMAGE_BASE64}</b>：Base64 编码图片，适用于图片内容已在内存中的场景</li>
 *   <li><b>{@link ImageInputMode#IMAGE_FILE}</b>：本地文件路径，构建时自动读取文件并转 Base64</li>
 * </ul>
 *
 * <h3>混合输入</h3>
 * <p>支持在同一条消息中混合多种输入类型，例如同时引用一张 URL 图片、一张 Base64 图片和文本提问，
 * 构建器会按添加顺序生成 content 数组，符合 OpenAI Vision API 的多内容块规范。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * MultimodalRequest request = multimodalRequestBuilder.newRequest()
 *         .addText("请对比这两张图片的视觉风格差异")
 *         .addImageUrl("https://example.com/a.png")
 *         .addImageBase64(base64Data, "image/png")
 *         .build();
 *
 * // 转换为 OpenAI Vision API 的 JSON 结构（可序列化后直接 POST）
 * List<OpenAiContentPart> parts = request.toOpenAiContentParts();
 *
 * // 转换为 LangChain4j UserMessage 供 ChatModel 消费
 * UserMessage userMessage = request.toUserMessage();
 * }</pre>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>{@link ImageInputMode#IMAGE_FILE} 模式下文件读取失败时，降级为文本占位符并记录警告，
 *       不抛出异常以保证调用链不中断</li>
 *   <li>当配置 {@code contentops.multimodal.enabled=false} 时，
 *       {@link #toUserMessage()} 退化为纯文本消息</li>
 * </ul>
 *
 * @see VisionAnalysisService
 */
@Slf4j
@Component
public class MultimodalRequestBuilder {

    private final MultimodalProperties properties;

    /**
     * 构造多模态请求构建器。
     *
     * @param properties 多模态配置属性
     */
    public MultimodalRequestBuilder(MultimodalProperties properties) {
        this.properties = properties;
        log.info("[Multimodal] 请求构建器已初始化, enabled={}, maxImages={}, detail={}",
                properties.isEnabled(), properties.getMaxImagesPerMessage(), properties.getDefaultDetail());
    }

    /**
     * 判断多模态能力是否已启用。
     *
     * @return true 表示启用，{@link #toUserMessage()} 将生成包含图片内容的消息
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 创建一个新的流式请求构建器。
     *
     * @return 流式构建器实例
     */
    public RequestBuilder newRequest() {
        return new RequestBuilder(properties);
    }

    /**
     * 快捷构建一条仅包含单张 URL 图片与文本的请求。
     *
     * @param text    文本内容（可为提问或描述）
     * @param imageUrl 图片 URL
     * @return 多模态请求
     */
    public MultimodalRequest buildUrlRequest(String text, String imageUrl) {
        return newRequest().addText(text).addImageUrl(imageUrl).build();
    }

    /**
     * 快捷构建一条仅包含单张 Base64 图片与文本的请求。
     *
     * @param text       文本内容
     * @param base64Data 图片 Base64 编码数据（不含 data: 前缀）
     * @param mimeType   图片 MIME 类型（如 image/jpeg）
     * @return 多模态请求
     */
    public MultimodalRequest buildBase64Request(String text, String base64Data, String mimeType) {
        return newRequest().addText(text).addImageBase64(base64Data, mimeType).build();
    }

    /**
     * 快捷构建一条仅包含单张本地文件图片与文本的请求。
     *
     * @param text     文本内容
     * @param filePath 本地图片文件路径
     * @return 多模态请求
     */
    public MultimodalRequest buildFileRequest(String text, String filePath) {
        return newRequest().addText(text).addImageFile(filePath).build();
    }

    // ════════════════════════════════════════════════════════════════
    //  流式构建器
    // ════════════════════════════════════════════════════════════════

    /**
     * 流式多模态请求构建器。
     *
     * <p>通过链式调用累积文本片段与图片输入，最终通过 {@link #build()} 生成
     * {@link MultimodalRequest}。构建过程会对图片数量、空输入进行校验。
     */
    public static final class RequestBuilder {

        private final MultimodalProperties properties;
        private final List<String> textSegments = new ArrayList<>();
        private final List<ImageInput> imageInputs = new ArrayList<>();

        private RequestBuilder(MultimodalProperties properties) {
            this.properties = properties;
        }

        /**
         * 追加一段文本内容。
         *
         * @param text 文本片段（为空时忽略）
         * @return 当前构建器，便于链式调用
         */
        public RequestBuilder addText(String text) {
            if (text != null && !text.isBlank()) {
                textSegments.add(text);
            }
            return this;
        }

        /**
         * 追加一张通过 URL 引用的图片。
         *
         * @param imageUrl 图片 URL（为空时忽略）
         * @return 当前构建器
         */
        public RequestBuilder addImageUrl(String imageUrl) {
            if (imageUrl != null && !imageUrl.isBlank()) {
                ensureCapacity();
                imageInputs.add(new ImageInput(ImageInputMode.IMAGE_URL, imageUrl, null, null));
            }
            return this;
        }

        /**
         * 追加一张 Base64 编码的图片。
         *
         * @param base64Data Base64 数据（不含 data: 前缀）
         * @param mimeType   MIME 类型，为空时默认 image/jpeg
         * @return 当前构建器
         */
        public RequestBuilder addImageBase64(String base64Data, String mimeType) {
            if (base64Data != null && !base64Data.isBlank()) {
                ensureCapacity();
                String mime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
                imageInputs.add(new ImageInput(ImageInputMode.IMAGE_BASE64, base64Data, null, mime));
            }
            return this;
        }

        /**
         * 追加一张本地文件图片。
         *
         * <p>实际文件读取在 {@link #build()} 时执行。若读取失败，将降级为
         * 文本占位符并记录警告，不中断构建流程。
         *
         * @param filePath 本地图片文件路径
         * @return 当前构建器
         */
        public RequestBuilder addImageFile(String filePath) {
            if (filePath != null && !filePath.isBlank()) {
                ensureCapacity();
                imageInputs.add(new ImageInput(ImageInputMode.IMAGE_FILE, filePath, null, null));
            }
            return this;
        }

        /**
         * 追加一个已构建好的图片输入（用于复用或高级场景）。
         *
         * @param input 图片输入
         * @return 当前构建器
         */
        public RequestBuilder addImage(ImageInput input) {
            if (input != null) {
                ensureCapacity();
                imageInputs.add(input);
            }
            return this;
        }

        /**
         * 构建多模态请求。
         *
         * @return 多模态请求实例
         * @throws IllegalStateException 当既无文本也无图片时
         */
        public MultimodalRequest build() {
            if (textSegments.isEmpty() && imageInputs.isEmpty()) {
                throw new IllegalStateException("多模态请求至少需要包含一段文本或一张图片");
            }

            // 合并文本片段
            String mergedText = String.join("\n\n", textSegments);

            // 解析图片输入，处理 IMAGE_FILE 模式的文件读取与降级
            List<ResolvedImage> resolvedImages = new ArrayList<>(imageInputs.size());
            for (ImageInput input : imageInputs) {
                ResolvedImage resolved = resolveImage(input);
                if (resolved != null) {
                    resolvedImages.add(resolved);
                }
            }

            return new MultimodalRequest(mergedText, Collections.unmodifiableList(resolvedImages));
        }

        /**
         * 校验图片数量是否超出上限。
         */
        private void ensureCapacity() {
            int limit = Math.max(1, properties.getMaxImagesPerMessage());
            if (imageInputs.size() >= limit) {
                throw new IllegalStateException(
                        "单条消息图片数量超出上限: " + limit + "，请减少图片或拆分为多条消息");
            }
        }

        /**
         * 解析单个图片输入，将 IMAGE_FILE 模式转换为 Base64 数据 URL。
         *
         * <p>降级策略：文件读取失败时返回文本占位描述而非 null 之外的异常。
         */
        private ResolvedImage resolveImage(ImageInput input) {
            return switch (input.mode()) {
                case IMAGE_URL -> new ResolvedImage(input.value(), null);
                case IMAGE_BASE64 -> {
                    String mime = input.mimeType() != null ? input.mimeType() : "image/jpeg";
                    String dataUrl = "data:" + mime + ";base64," + input.value();
                    yield new ResolvedImage(dataUrl, null);
                }
                case IMAGE_FILE -> resolveFileImage(input.value());
            };
        }

        /**
         * 读取本地图片文件并转为 data URL。
         */
        private ResolvedImage resolveFileImage(String filePath) {
            try {
                Path path = Path.of(filePath);
                if (!Files.exists(path)) {
                    log.warn("[Multimodal] 图片文件不存在, 降级为文本占位: {}", filePath);
                    return new ResolvedImage(null, "[图片不可用: 文件不存在 " + filePath + "]");
                }
                byte[] bytes = Files.readAllBytes(path);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String mime = probeMimeType(path, bytes);
                String dataUrl = "data:" + mime + ";base64," + base64;
                return new ResolvedImage(dataUrl, null);
            } catch (IOException e) {
                log.warn("[Multimodal] 读取图片文件失败, 降级为文本占位: {}, 错误: {}", filePath, e.getMessage());
                return new ResolvedImage(null, "[图片不可用: 读取失败 " + filePath + "]");
            } catch (Exception e) {
                log.warn("[Multimodal] 处理图片文件异常, 降级为文本占位: {}, 错误: {}", filePath, e.getMessage());
                return new ResolvedImage(null, "[图片不可用: " + e.getMessage() + "]");
            }
        }

        /**
         * 探测图片 MIME 类型。
         */
        private String probeMimeType(Path path, byte[] bytes) {
            String fileName = path.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".png")) return "image/png";
            if (fileName.endsWith(".gif")) return "image/gif";
            if (fileName.endsWith(".webp")) return "image/webp";
            if (fileName.endsWith(".bmp")) return "image/bmp";
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
            // 通过文件头魔数判断
            if (bytes.length >= 4) {
                if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50) return "image/png";
                if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
                if ((bytes[0] & 0xFF) == 0x47 && (bytes[1] & 0xFF) == 0x49) return "image/gif";
            }
            return "image/jpeg";
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  数据结构定义
    // ════════════════════════════════════════════════════════════════

    /**
     * 图片输入模式。
     */
    public enum ImageInputMode {
        /** 通过 URL 引用图片 */
        IMAGE_URL,
        /** Base64 编码图片 */
        IMAGE_BASE64,
        /** 本地文件路径 */
        IMAGE_FILE
    }

    /**
     * 图片输入描述（构建期原始数据）。
     *
     * @param mode     输入模式
     * @param value    URL / Base64 数据 / 文件路径
     * @param detail   OpenAI Vision 细节级别（low/high/auto），为空时使用默认
     * @param mimeType MIME 类型（仅 IMAGE_BASE64 模式使用）
     */
    public record ImageInput(ImageInputMode mode, String value, String detail, String mimeType) {
        /**
         * 构造图片输入并进行非空校验。
         */
        public ImageInput {
            Objects.requireNonNull(mode, "图片输入模式不能为空");
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("图片输入值不能为空");
            }
        }
    }

    /**
     * 解析后的图片（构建期产物），包含可直接用于 OpenAI Vision 的 data URL 或 http URL，
     * 以及降级时的文本占位描述。
     *
     * @param url            图片 URL（http 或 data: 协议），降级时为 null
     * @param fallbackText   降级文本占位，正常时为 null
     */
    private record ResolvedImage(String url, String fallbackText) {
    }

    /**
     * 多模态请求，包含合并后的文本与已解析的图片列表。
     *
     * <p>可转换为：
     * <ul>
     *   <li>OpenAI Vision API 的 content 数组结构（{@link #toOpenAiContentParts()}）</li>
     *   <li>LangChain4j 的 {@link UserMessage}（{@link #toUserMessage()}）</li>
     * </ul>
     */
    public static final class MultimodalRequest {

        private final String text;
        private final List<ResolvedImage> images;

        private MultimodalRequest(String text, List<ResolvedImage> images) {
            this.text = text;
            this.images = images;
        }

        /**
         * 获取合并后的文本内容。
         *
         * @return 文本内容，可能为空字符串
         */
        public String getText() {
            return text;
        }

        /**
         * 获取图片数量。
         *
         * @return 图片数量
         */
        public int getImageCount() {
            return images.size();
        }

        /**
         * 转换为 OpenAI Vision API 的 content 数组结构。
         *
         * <p>生成的结构可直接通过 Jackson 序列化为 JSON 并作为 chat completions
         * 请求中 message 的 content 字段。
         *
         * @return OpenAI content 部分数组
         */
        public List<OpenAiContentPart> toOpenAiContentParts() {
            List<OpenAiContentPart> parts = new ArrayList<>();
            if (text != null && !text.isBlank()) {
                parts.add(new OpenAiContentPart("text", text, null));
            }
            for (ResolvedImage image : images) {
                if (image.url() != null) {
                    parts.add(new OpenAiContentPart("image_url", null,
                            new OpenAiImageUrl(image.url())));
                } else if (image.fallbackText() != null) {
                    parts.add(new OpenAiContentPart("text", image.fallbackText(), null));
                }
            }
            return parts;
        }

        /**
         * 转换为 LangChain4j 的 {@link UserMessage}。
         *
         * <p>当多模态未启用或所有图片均降级为文本时，退化为纯文本消息。
         *
         * @param enabled 是否启用多模态（false 时图片内容降级为文本占位）
         * @return LangChain4j 用户消息
         */
        public UserMessage toUserMessage(boolean enabled) {
            List<Content> contents = new ArrayList<>();

            if (text != null && !text.isBlank()) {
                contents.add(TextContent.from(text));
            }

            if (enabled) {
                for (ResolvedImage image : images) {
                    if (image.url() != null) {
                        contents.add(ImageContent.from(Image.builder().url(image.url()).build()));
                    } else if (image.fallbackText() != null) {
                        contents.add(TextContent.from(image.fallbackText()));
                    }
                }
            } else {
                // 多模态未启用，所有图片降级为不可用提示
                for (ResolvedImage image : images) {
                    String placeholder = image.fallbackText() != null
                            ? image.fallbackText()
                            : "[图片内容已省略：多模态能力未启用]";
                    contents.add(TextContent.from(placeholder));
                }
            }

            if (contents.isEmpty()) {
                return UserMessage.from("");
            }
            return UserMessage.from(contents.toArray(new Content[0]));
        }

        /**
         * 转换为 LangChain4j 的 {@link UserMessage}（多模态启用版本）。
         *
         * @return LangChain4j 用户消息
         */
        public UserMessage toUserMessage() {
            return toUserMessage(true);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  OpenAI Vision API 序列化 DTO
    // ════════════════════════════════════════════════════════════════

    /**
     * OpenAI Vision API 的 content 数组元素。
     *
     * <p>type 为 "text" 时使用 text 字段；type 为 "image_url" 时使用 imageUrl 字段。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpenAiContentPart {
        /** 内容类型：text 或 image_url */
        @JsonProperty("type")
        private final String type;

        /** 文本内容（type=text 时使用） */
        @JsonProperty("text")
        private final String text;

        /** 图片 URL 对象（type=image_url 时使用） */
        @JsonProperty("image_url")
        private final OpenAiImageUrl imageUrl;

        /**
         * 构造 content 部分。
         *
         * @param type      类型
         * @param text      文本
         * @param imageUrl  图片 URL 对象
         */
        public OpenAiContentPart(String type, String text, OpenAiImageUrl imageUrl) {
            this.type = type;
            this.text = text;
            this.imageUrl = imageUrl;
        }
    }

    /**
     * OpenAI Vision API 的 image_url 对象。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpenAiImageUrl {
        /** 图片 URL（http 或 data: 协议） */
        @JsonProperty("url")
        private final String url;

        /**
         * 构造图片 URL 对象。
         *
         * @param url 图片 URL
         */
        public OpenAiImageUrl(String url) {
            this.url = url;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  配置属性
    // ════════════════════════════════════════════════════════════════

    /**
     * 多模态能力配置属性。
     *
     * <p>通过 {@code contentops.multimodal.*} 在 application.yml 中绑定。
     *
     * <h3>配置示例</h3>
     * <pre>{@code
     * contentops:
     *   multimodal:
     *     enabled: true
     *     max-images-per-message: 5
     *     default-detail: high
     *     vision-model: gpt-4o
     * }</pre>
     */
    @Data
    @Component
    @ConfigurationProperties(prefix = "contentops.multimodal")
    public static class MultimodalProperties {

        /** 是否启用多模态能力（关闭时图片内容降级为文本占位） */
        private boolean enabled = true;

        /** 单条消息允许的最大图片数量 */
        private int maxImagesPerMessage = 5;

        /** 默认图片细节级别（low/high/auto），影响 token 消耗与清晰度 */
        private String defaultDetail = "auto";

        /** 视觉理解使用的默认模型名称（如 gpt-4o、gpt-4-vision-preview） */
        private String visionModel = "gpt-4o";
    }
}
