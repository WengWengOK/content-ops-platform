package com.contentops.publish.render;

import com.contentops.common.render.PngRenderClient;
import com.contentops.common.render.RenderServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 渲染相关 Bean 装配：卡片溢出测量器（真实渲染 → 估算降级）。
 */
@Configuration
public class RenderConfig {

    @Bean
    public CardMeasurer cardMeasurer(PngRenderClient pngRenderClient,
                                     RenderServiceProperties properties) {
        return new RenderServiceCardMeasurer(pngRenderClient, properties);
    }
}
