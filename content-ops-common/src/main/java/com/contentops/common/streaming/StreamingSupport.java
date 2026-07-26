package com.contentops.common.streaming;

import com.contentops.common.enums.AgentStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式响应支持工具类（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>提供流式响应的配置判断和常用处理器工厂方法，供各 Agent 模块在
 * 需要启用流式输出时使用。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>{@link #isStreamingEnabledForStage} — 判断指定阶段是否启用流式</li>
 *   <li>{@link #createAccumulatingHandler} — 创建累积式处理器（将 chunk 拼接为完整响应）</li>
 *   <li>{@link #createLoggingHandler} — 创建日志式处理器（记录每个 chunk 用于调试）</li>
 *   <li>{@link #streamToFuture} — 将流式回调转换为 CompletableFuture（阻塞等待完整结果）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * if (streamingSupport.isStreamingEnabledForStage(AgentStage.CONTENT_CREATION)) {
 *     StreamingResponseHandler handler = streamingSupport.createAccumulatingHandler();
 *     streamingChatModel.generate(prompt, toStreamingHandler(handler));
 *     // handler.onComplete 中可获取完整结果
 * }
 * }</pre>
 *
 * <p><b>注意：</b>LangChain4j 的 {@code StreamingChatModel} 需要
 * {@code langchain4j-open-ai-spring-boot-starter} 配置 {@code streaming=true}
 * 才能创建 {@code StreamingChatModel} bean。本工具类不直接依赖
 * {@code StreamingChatModel}，而是提供处理器和配置判断能力。
 *
 * @see StreamingProperties
 * @see StreamingResponseHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingSupport {

    private final StreamingProperties properties;

    /**
     * 判断流式响应是否全局启用。
     *
     * @return true 表示流式功能已开启
     */
    public boolean isStreamingEnabled() {
        return properties.isEnabled();
    }

    /**
     * 判断指定 Agent 阶段是否启用流式响应。
     *
     * <p>当流式全局启用且该阶段在配置的 stages 列表中时返回 true。
     *
     * @param stage Agent 阶段
     * @return true 表示该阶段应使用流式响应
     */
    public boolean isStreamingEnabledForStage(AgentStage stage) {
        if (!properties.isEnabled()) {
            return false;
        }
        boolean enabled = properties.getStages() != null
                && properties.getStages().contains(stage.getCode());
        if (enabled) {
            log.debug("[Streaming] stage={} 启用流式响应", stage.getCode());
        }
        return enabled;
    }

    /**
     * 创建一个累积式流式响应处理器。
     *
     * <p>该处理器将所有收到的 chunk 追加到内部 StringBuilder 中，
     * 在 {@link StreamingResponseHandler#onComplete} 时传入完整拼接结果。
     * 适用于需要获取完整结果后再做后续处理（如质量评估）的场景。
     *
     * @return 累积式处理器实例
     */
    public StreamingResponseHandler createAccumulatingHandler() {
        return new AccumulatingHandler();
    }

    /**
     * 创建一个日志式流式响应处理器。
     *
     * <p>该处理器在收到每个 chunk 时记录 DEBUG 日志，适用于调试流式输出。
     *
     * @param stageName 阶段名称（用于日志标识）
     * @return 日志式处理器实例
     */
    public StreamingResponseHandler createLoggingHandler(String stageName) {
        return new LoggingHandler(stageName);
    }

    /**
     * 创建一个带 Future 的流式响应处理器。
     *
     * <p>返回的 {@link StreamFutureHolder} 同时包含处理器和 Future：
     * 调用方将 {@code holder.getHandler()} 传递给 {@code StreamingChatModel.generate()}，
     * 然后通过 {@code holder.getFuture().get()} 阻塞等待完整结果。
     *
     * <h3>使用方式</h3>
     * <pre>{@code
     * StreamFutureHolder holder = streamingSupport.streamToFuture("content-creation");
     * streamingChatModel.generate(prompt, toLangChain4jHandler(holder.getHandler()));
     * String fullResponse = holder.getFuture().get(); // 阻塞等待
     * }</pre>
     *
     * @param stageName 阶段名称（用于日志）
     * @return 包含处理器和 Future 的持有对象
     */
    public StreamFutureHolder streamToFuture(String stageName) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StreamingResponseHandler handler = new StreamingResponseHandler() {
            @Override
            public void onPartialResponse(String chunk) {
                log.debug("[Streaming:{}] 收到 chunk, 长度={}", stageName, chunk.length());
            }

            @Override
            public void onComplete(String fullResponse) {
                log.info("[Streaming:{}] 流式完成, 总长度={}", stageName, fullResponse.length());
                future.complete(fullResponse);
            }

            @Override
            public void onError(Throwable error) {
                log.error("[Streaming:{}] 流式出错", stageName, error);
                future.completeExceptionally(error);
            }
        };
        return new StreamFutureHolder(handler, future);
    }

    /**
     * 创建一个带回调的累积式处理器，并在完成时触发回调。
     *
     * @param onCompleteCallback 完成时的回调函数，接收完整响应
     * @return 流式响应处理器
     */
    public StreamingResponseHandler createCallbackHandler(java.util.function.Consumer<String> onCompleteCallback) {
        return new StreamingResponseHandler() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onPartialResponse(String chunk) {
                buffer.append(chunk);
            }

            @Override
            public void onComplete(String fullResponse) {
                onCompleteCallback.accept(fullResponse);
            }

            @Override
            public void onError(Throwable error) {
                log.error("[Streaming] 回调处理器出错", error);
            }
        };
    }

    // ──────────────── 内置处理器实现 ────────────────

    /**
     * 累积式处理器 —— 将所有 chunk 拼接为完整响应。
     */
    private static class AccumulatingHandler implements StreamingResponseHandler {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onPartialResponse(String chunk) {
            buffer.append(chunk);
        }

        @Override
        public void onComplete(String fullResponse) {
            // 使用 LangChain4j 传入的完整结果（如果有），否则使用累积结果
            // 两者通常一致
        }

        @Override
        public void onError(Throwable error) {
            // 错误处理由日志记录
            log.error("[Streaming:Accumulating] 流式出错", error);
        }

        /**
         * 获取累积的完整响应文本。
         *
         * @return 累积的完整文本
         */
        public String getAccumulatedResponse() {
            return buffer.toString();
        }
    }

    /**
     * 日志式处理器 —— 记录每个 chunk 用于调试。
     */
    private static class LoggingHandler implements StreamingResponseHandler {
        private final String stageName;
        private final AtomicReference<String> lastResponse = new AtomicReference<>("");

        LoggingHandler(String stageName) {
            this.stageName = stageName;
        }

        @Override
        public void onPartialResponse(String chunk) {
            log.debug("[Streaming:{}] chunk: {}", stageName, chunk);
        }

        @Override
        public void onComplete(String fullResponse) {
            lastResponse.set(fullResponse);
            log.info("[Streaming:{}] 完成, 总长度={}", stageName, fullResponse.length());
        }

        @Override
        public void onError(Throwable error) {
            log.error("[Streaming:{}] 出错", stageName, error);
        }
    }

    /**
     * 流式响应 Future 持有对象。
     *
     * <p>同时包含 {@link StreamingResponseHandler} 和 {@link CompletableFuture}，
     * 调用方将 handler 传递给 {@code StreamingChatModel}，通过 future 阻塞等待结果。
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class StreamFutureHolder {
        /** 流式响应处理器，传递给 StreamingChatModel */
        private final StreamingResponseHandler handler;
        /** 完整响应的 Future，在流式完成时完成 */
        private final CompletableFuture<String> future;
    }
}
