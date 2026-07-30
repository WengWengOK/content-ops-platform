package com.contentops.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Data Analysis Agent microservice entry point.
 *
 * <p>{@code excludeName} disables LangChain4j's declarative {@code @AiService} auto-scanner
 * ({@code dev.langchain4j.spring.LangChain4jAutoConfig}, which imports
 * {@code AiServiceScannerProcessor}). This allows
 * {@link com.contentops.analysis.config.AnalysisAgentConfig} to be the single source of the
 * {@link com.contentops.analysis.agent.DataAnalysisAgent} bean, built programmatically via
 * {@code AiServices.builder()}, instead of the scanner auto-registering a second bean of the
 * same type and clashing with the explicit {@code @Bean} definition. The OpenAI
 * {@code ChatModel} bean (from {@code langchain4j-open-ai-spring-boot-starter}) is unaffected.
 */
@SpringBootApplication(
        scanBasePackages = {"com.contentops.analysis", "com.contentops.common"},
        excludeName = "dev.langchain4j.spring.LangChain4jAutoConfig"
)
@EnableDiscoveryClient
public class AnalysisAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalysisAgentApplication.class, args);
    }
}
