package com.contentops.orchestrator.config;

import com.contentops.common.util.WorkflowStateManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class OrchestratorConfig {

    @Bean
    public WorkflowStateManager workflowStateManager(StringRedisTemplate redisTemplate,
                                                      JdbcTemplate jdbcTemplate) {
        return new WorkflowStateManager(redisTemplate, jdbcTemplate);
    }
}
