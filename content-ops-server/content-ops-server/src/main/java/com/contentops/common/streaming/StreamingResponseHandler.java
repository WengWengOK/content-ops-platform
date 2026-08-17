package com.contentops.common.streaming;

/**
 * 流式响应处理器接口（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>定义 LangChain4j 流式响应回调的统一接口，供各 Agent 模块在启用流式输出时
 * 实现增量回调逻辑。LangChain4j 的 {@code StreamingChatModel} 会在生成过程中
 * 不断回调 {@link #onPartialResponse}，最终回调 {@link #onComplete}。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * StreamingResponseHandler handler = new StreamingResponseHandler() {
 *     @Override
 *     public void onPartialResponse(String chunk) {
 *         // 通过 SSE/WebSocket 推送增量内容到前端
 *         sseEmitter.send(chunk);
 *     }
 *
 *     @Override
 *     public void onComplete(String fullResponse) {
 *         sseEmitter.complete();
 *         // 对完整结果进行质量评估
 *         qualityAssessmentService.assessQuality(stage, fullResponse);
 *     }
 *
 *     @Override
 *     public void onError(Throwable error) {
 *         sseEmitter.completeWithError(error);
 *     }
 * };
 * }</pre>
 *
 * @see StreamingSupport
 */
public interface StreamingResponseHandler {

    /**
     * 收到部分响应（增量 chunk）时回调。
     *
     * <p>LangChain4j 在流式生成过程中会多次调用此方法，每次传入一小段文本。
     *
     * @param chunk 本次增量文本片段
     */
    void onPartialResponse(String chunk);

    /**
     * 流式生成完成时回调。
     *
     * @param fullResponse 完整的生成结果（所有 chunk 拼接后的全文）
     */
    void onComplete(String fullResponse);

    /**
     * 流式生成过程中发生错误时回调。
     *
     * @param error 异常对象
     */
    void onError(Throwable error);
}
