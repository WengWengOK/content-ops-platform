package com.contentops.topic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Topic Planning Agent microservice entry point.
 *
 * <p>{@code excludeName} disables LangChain4j's declarative {@code @AiService} auto-scanner
 * ({@code dev.langchain4j.spring.LangChain4jAutoConfig}, which imports
 * {@code AiServiceScannerProcessor}). This allows
 * {@link com.contentops.topic.config.TopicAgentConfig} to be the single source of the
 * {@link com.contentops.topic.agent.TopicPlanningAgent} bean, built programmatically via
 * {@code AiServices.builder()}, instead of the scanner auto-registering a second bean of the
 * same type and clashing with the explicit {@code @Bean} definition. The OpenAI
 * {@code ChatModel} bean (from {@code langchain4j-open-ai-spring-boot-starter}) is unaffected.
 */
@SpringBootApplication(
        scanBasePackages = {"com.contentops.topic", "com.contentops.common"},
        excludeName = "dev.langchain4j.spring.LangChain4jAutoConfig"
)
@EnableDiscoveryClient
public class TopicAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TopicAgentApplication.class, args);
    }
}
