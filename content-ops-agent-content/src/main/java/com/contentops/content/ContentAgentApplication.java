package com.contentops.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Content Creation Agent microservice entry point.
 *
 * <p>{@code excludeName} disables LangChain4j's declarative {@code @AiService} auto-scanner
 * ({@code dev.langchain4j.spring.LangChain4jAutoConfig}, which imports
 * {@code AiServiceScannerProcessor}). This allows
 * {@link com.contentops.content.config.ContentAgentConfig} to be the single source of the
 * {@link com.contentops.content.agent.ContentCreationAgent} bean, built programmatically via
 * {@code AiServices.builder()}, instead of the scanner auto-registering a second bean of the
 * same type and clashing with the explicit {@code @Bean} definition. The OpenAI
 * {@code ChatModel} bean (from {@code langchain4j-open-ai-spring-boot-starter}) is unaffected.
 */
@SpringBootApplication(
        scanBasePackages = {"com.contentops.content", "com.contentops.common"},
        excludeName = "dev.langchain4j.spring.LangChain4jAutoConfig"
)
@EnableDiscoveryClient
@EnableKafka
public class ContentAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentAgentApplication.class, args);
    }
}
