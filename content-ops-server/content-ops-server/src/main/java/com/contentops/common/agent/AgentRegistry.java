package com.contentops.common.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 注册表（控制面）：集中声明平台全部 Agent 的元数据。
 *
 * <p>大厂多 Agent 平台由注册表驱动：编排器按阶段查表路由到 Agent，
 * 前端按注册表渲染 Agent 管线，治理层按注册表配置限流/预算/权限。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final Map<String, AgentDescriptor> agents = new LinkedHashMap<>();

    public AgentRegistry() {
        register(AgentDescriptor.builder()
                .code("topic-planning").name("选题 Agent")
                .description("联网调研 + 热点选题 + 竞品分析，产出 3-5 个选题候选")
                .stage("topic-planning")
                .capabilities(List.of("热点调研", "选题生成", "竞品分析"))
                .tools(List.of("searchTrendingTopics", "analyzeCompetitors", "getTrendingHotspots"))
                .modelTier("creative").humanInLoop(true).streaming(false).qualityGate(true).build());
        register(AgentDescriptor.builder()
                .code("content-creation").name("内容 Agent")
                .description("大纲 → 初稿渐进式生成，含标题变体与标签")
                .stage("content-creation")
                .capabilities(List.of("大纲生成", "初稿创作", "标题优化"))
                .tools(List.of("FileTools"))
                .modelTier("creative").humanInLoop(true).streaming(false).qualityGate(true).build());
        register(AgentDescriptor.builder()
                .code("image-design").name("配图 Agent")
                .description("封面/配图风格方案与批量生图（ARK 模型未开通时降级占位）")
                .stage("image-design")
                .capabilities(List.of("风格方案", "封面生成", "配图生成"))
                .tools(List.of("ImageGenerationService"))
                .modelTier("creative").humanInLoop(true).streaming(false).qualityGate(false).build());
        register(AgentDescriptor.builder()
                .code("publishing").name("发布 Agent")
                .description("按平台格式化内容并打包 ZIP（卡片 HTML + 封面 + 校验报告）")
                .stage("publishing")
                .capabilities(List.of("平台适配", "排版", "ZIP 打包"))
                .tools(List.of())
                .modelTier("formatting").humanInLoop(false).streaming(false).qualityGate(true).build());
        register(AgentDescriptor.builder()
                .code("data-analysis").name("数据分析 Agent")
                .description("作品数据分析（独立服务模式）")
                .stage("data-analysis")
                .capabilities(List.of("数据统计", "效果分析"))
                .tools(List.of())
                .modelTier("formatting").humanInLoop(false).streaming(false).qualityGate(false)
                .standaloneService(true).build());
        register(AgentDescriptor.builder()
                .code("optimization").name("优化 Agent")
                .description("基于反馈迭代优化作品（独立服务模式）")
                .stage("optimization")
                .capabilities(List.of("内容优化", "标题迭代"))
                .tools(List.of())
                .modelTier("formatting").humanInLoop(false).streaming(false).qualityGate(false)
                .standaloneService(true).build());
        register(AgentDescriptor.builder()
                .code("discussion").name("选题讨论 Agent")
                .description("多轮对话式选题探索，支持 SSE 流式输出")
                .stage("discussion")
                .capabilities(List.of("多轮对话", "选题澄清", "方向拆解"))
                .tools(List.of("TopicResearchTools"))
                .modelTier("creative").humanInLoop(true).streaming(true).qualityGate(false).build());
        register(AgentDescriptor.builder()
                .code("trend-analysis").name("热点分析 Agent")
                .description("热点相关性/可信度/摘要 AI 分析")
                .stage("trend-analysis")
                .capabilities(List.of("相关性评分", "真假识别", "摘要"))
                .tools(List.of())
                .modelTier("formatting").humanInLoop(false).streaming(false).qualityGate(false).build());
        log.info("[AgentRegistry] 已注册 {} 个 Agent", agents.size());
    }

    private void register(AgentDescriptor agent) {
        agents.put(agent.getCode(), agent);
    }

    public List<AgentDescriptor> all() {
        return new ArrayList<>(agents.values());
    }

    public AgentDescriptor get(String code) {
        return agents.get(code);
    }
}
