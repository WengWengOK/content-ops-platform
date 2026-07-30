package com.contentops.orchestrator.config;

import com.contentops.common.util.WorkflowStateManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class OrchestratorConfig {

    @Bean
    public WorkflowStateManager workflowStateManager(StringRedisTemplate redisTemplate) {
        return new WorkflowStateManager(redisTemplate);
    }
}
