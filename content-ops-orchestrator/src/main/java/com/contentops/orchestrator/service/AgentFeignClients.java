package com.contentops.orchestrator.service;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.event.AgentTaskRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Feign clients for all 6 agent services.
 * Each agent is a separate microservice registered with Eureka.
 */
public class AgentFeignClients {

    @FeignClient(name = AgentConstants.SERVICE_TOPIC)
    public interface TopicAgentClient {
        @PostMapping("/api/v1/topic/execute")
        AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
    }

    @FeignClient(name = AgentConstants.SERVICE_CONTENT)
    public interface ContentAgentClient {
        @PostMapping("/api/v1/content/execute")
        AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
    }

    @FeignClient(name = AgentConstants.SERVICE_IMAGE)
    public interface ImageAgentClient {
        @PostMapping("/api/v1/image/execute")
        AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
    }

    @FeignClient(name = AgentConstants.SERVICE_PUBLISH)
    public interface PublishAgentClient {
        @PostMapping("/api/v1/publish/execute")
        AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
    }

    @FeignClient(name = AgentConstants.SERVICE_ANALYSIS)
    public interface AnalysisAgentClient {
        @PostMapping("/api/v1/analysis/execute")
        AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
    }

    @FeignClient(name = AgentConstants.SERVICE_OPTIMIZE)
    public interface OptimizeAgentClient {
        @PostMapping("/api/v1/optimize/execute")
        AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
    }
}
