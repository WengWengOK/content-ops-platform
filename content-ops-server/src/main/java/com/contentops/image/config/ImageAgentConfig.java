package com.contentops.image.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.profile.style.StyleEnricher;
import com.contentops.common.prompt.PromptFragmentService;
import com.contentops.common.prompt.PromptVersionService;
import com.contentops.image.agent.ImageDesignAgent;
import com.contentops.image.tool.ImageTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link ImageDesignAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 *
 * <p><b>P1: 动态 Prompt 拼接</b>——当 {@code contentops.prompt.enabled=true} 时，
 * 通过 {@link PromptFragmentService#assembleImageSystemMessage} 动态组装系统提示词，
 * 根据文章调性和目标平台追加专属指导片段，并支持 A/B 测试变体。
 * 关闭时回退到 {@link ImageDesignAgent} 上的 {@code @SystemMessage} 注解。
 */
@Configuration
public class ImageAgentConfig {

    /** 风格注入器（可选注入，未配置时降级为空） */
    @Autowired(required = false)
    private StyleEnricher styleEnricher;

    @Bean
    public ImageDesignAgent imageDesignAgent(ChatModel chatModel,
                                              ImageTools imageTools,
                                              RedisChatMemoryProvider chatMemoryProvider,
                                              FileTools fileTools,
                                              PromptFragmentService promptFragmentService,
                                              PromptVersionService promptVersionService) {
        var builder = AiServices.builder(ImageDesignAgent.class)
                .chatModel(chatModel)
                .tools(imageTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider);

        // P1: 动态 Prompt 拼接——启用时用 PromptFragmentService 组装，否则回退到 @SystemMessage
        // P0增强：动态 Prompt 拼接时，额外注入视觉风格画像保持配图一致性
        if (promptVersionService.isDynamicPromptEnabled()) {
            builder.systemMessageProvider(variables -> {
                String basePrompt = promptFragmentService.assembleImageSystemMessage(variables);
                // 注入视觉风格画像（保持配图色调/排版/风格一致）
                if (styleEnricher != null) {
                    String accountId = extractAccountId(variables);
                    basePrompt = styleEnricher.enrichImageDesignPrompt(accountId, basePrompt);
                }
                return basePrompt;
            });
        }

        return builder.build();
    }

    /**
     * 从变量Map中提取accountId。
     */
    private String extractAccountId(Object variables) {
        if (variables instanceof java.util.Map<?, ?> map) {
            Object id = map.get("accountId");
            return id != null ? id.toString() : null;
        }
        return null;
    }
}
