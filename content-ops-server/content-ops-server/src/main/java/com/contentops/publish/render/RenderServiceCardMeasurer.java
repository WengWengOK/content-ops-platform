package com.contentops.publish.render;

import com.contentops.common.render.PngRenderClient;
import com.contentops.common.render.RenderServiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于渲染服务（无头浏览器）的真实溢出测量；服务不可用或未启用时回退估算。
 */
@Slf4j
@RequiredArgsConstructor
public class RenderServiceCardMeasurer implements CardMeasurer {

    private final PngRenderClient pngRenderClient;
    private final RenderServiceProperties properties;

    @Override
    public OverflowResult measureOverflow(String cardHtml, int width, int height,
                                          int estimatedOverflow) {
        if (!properties.isEnabled()) {
            return OverflowResult.estimate(estimatedOverflow);
        }
        Integer measured = pngRenderClient.measureOverflow(cardHtml, width, height);
        if (measured == null) {
            log.debug("[CardMeasure] render service unavailable, fallback to estimate: {}px",
                    estimatedOverflow);
            return OverflowResult.estimate(estimatedOverflow);
        }
        return new OverflowResult(Math.max(0, measured), true);
    }
}
