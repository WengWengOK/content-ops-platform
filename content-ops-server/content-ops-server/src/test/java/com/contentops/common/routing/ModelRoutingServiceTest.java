package com.contentops.common.routing;

import com.contentops.common.enums.AgentStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型路由单元测试 — 锁定「创意类/格式化类 deepseek-chat + 差异化温度」策略，
 * 防止后续改动破坏 AiModelConfig 依赖的路由结果。
 */
class ModelRoutingServiceTest {

    @Test
    @DisplayName("创意类阶段使用高温度，格式化类阶段使用低温度")
    void routingSelectsModelPerStage() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        properties.setEnabled(true);
        ModelRoutingService routing = new ModelRoutingService(properties);

        ModelConfig creative = routing.getModelConfig(AgentStage.TOPIC_PLANNING);
        assertEquals("deepseek-chat", creative.getModelName());
        assertEquals(0.8, creative.getTemperature(), 0.001);
        assertTrue(creative.isCreative());

        ModelConfig content = routing.getModelConfig(AgentStage.CONTENT_CREATION);
        assertEquals("deepseek-chat", content.getModelName());

        ModelConfig formatting = routing.getModelConfig(AgentStage.PUBLISHING);
        assertEquals("deepseek-chat", formatting.getModelName());
        assertEquals(0.3, formatting.getTemperature(), 0.001);
        assertFalse(formatting.isCreative());

        ModelConfig analysis = routing.getModelConfig(AgentStage.DATA_ANALYSIS);
        assertEquals("deepseek-chat", analysis.getModelName());
    }
}
