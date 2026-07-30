package com.contentops.common.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nacos MCP 工具发现（模拟实现）。
 *
 * <p>使用本地 Map 模拟 Nacos 服务注册与发现，不依赖实际的 Nacos 客户端。
 * 启动时将本服务的 MCP 工具注册到模拟注册中心，并提供跨服务工具发现能力。
 *
 * <p><b>模拟注册中心数据结构</b>：
 * <pre>
 * serviceName ───┬── toolName1 → McpToolDescriptor
 *                ├── toolName2 → McpToolDescriptor
 *                └── toolName3 → McpToolDescriptor
 * </pre>
 *
 * <p><b>核心方法</b>：
 * <ul>
 *   <li>{@link #registerTool(String, McpToolDescriptor)} — 将工具注册到指定服务名下</li>
 *   <li>{@link #discoverTools(String)} — 发现指定服务名下的所有可用工具</li>
 *   <li>{@link #discoverAllTools()} — 发现注册中心中所有服务的所有工具</li>
 * </ul>
 *
 * <p>通过 {@code contentops.mcp.nacos.enabled=true} 启用。
 * 启动后自动将 {@link McpToolRegistry} 中的工具注册到本服务名下。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "contentops.mcp.nacos.enabled", havingValue = "true")
public class NacosMcpRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final McpToolRegistry mcpToolRegistry;
    private final NacosMcpProperties nacosMcpProperties;

    /**
     * 模拟 Nacos 注册中心：serviceName → (toolName → descriptor)
     */
    private final Map<String, Map<String, McpToolDescriptor>> serviceRegistry = new ConcurrentHashMap<>();

    /** 防止在父子容器中重复注册 */
    private final AtomicBoolean registered = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!registered.compareAndSet(false, true)) {
            return;
        }

        String serviceName = nacosMcpProperties.getServiceName();
        log.info("[Nacos MCP] 开始将本服务工具注册到模拟 Nacos: serviceName={}, serverAddr={}, namespace={}",
                serviceName, nacosMcpProperties.getServerAddr(), nacosMcpProperties.getNamespace());

        // 将 McpToolRegistry 中所有工具注册到本服务名下
        int count = 0;
        for (McpToolDescriptor descriptor : mcpToolRegistry.listTools()) {
            registerTool(serviceName, descriptor);
            count++;
        }

        log.info("[Nacos MCP] 注册完成: serviceName={}, 注册工具数={}, 注册中心总服务数={}",
                serviceName, count, serviceRegistry.size());
    }

    /**
     * 将工具注册到模拟 Nacos 注册中心。
     *
     * @param serviceName 服务名称
     * @param descriptor  工具描述符
     */
    public void registerTool(String serviceName, McpToolDescriptor descriptor) {
        if (serviceName == null || descriptor == null || descriptor.getToolName() == null) {
            log.warn("[Nacos MCP] 跳过无效的注册请求: serviceName={}, descriptor={}", serviceName, descriptor);
            return;
        }

        serviceRegistry
                .computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>())
                .put(descriptor.getToolName(), descriptor);

        log.info("[Nacos MCP] 工具已注册: serviceName={}, toolName={}",
                serviceName, descriptor.getToolName());
    }

    /**
     * 发现指定服务名下的所有可用工具。
     *
     * @param serviceName 服务名称
     * @return 工具描述符列表，若服务不存在返回空列表
     */
    public List<McpToolDescriptor> discoverTools(String serviceName) {
        Map<String, McpToolDescriptor> tools = serviceRegistry.get(serviceName);
        if (tools == null || tools.isEmpty()) {
            log.info("[Nacos MCP] 未找到服务 {} 的工具", serviceName);
            return Collections.emptyList();
        }
        log.info("[Nacos MCP] 发现服务 {} 的工具: {} 个", serviceName, tools.size());
        return new ArrayList<>(tools.values());
    }

    /**
     * 发现注册中心中所有服务的所有工具。
     *
     * @return 所有工具描述符列表
     */
    public List<McpToolDescriptor> discoverAllTools() {
        List<McpToolDescriptor> allTools = new ArrayList<>();
        for (Map<String, McpToolDescriptor> tools : serviceRegistry.values()) {
            allTools.addAll(tools.values());
        }
        log.info("[Nacos MCP] 发现所有服务的工具: {} 个 (服务数: {})", allTools.size(), serviceRegistry.size());
        return allTools;
    }

    /**
     * 获取注册中心中所有已注册的服务名称。
     *
     * @return 服务名称集合
     */
    public Collection<String> listServices() {
        return Collections.unmodifiableCollection(serviceRegistry.keySet());
    }

    /**
     * 从指定服务中注销工具。
     *
     * @param serviceName 服务名称
     * @param toolName    工具名称
     * @return 被移除的工具描述符，若不存在返回 null
     */
    public McpToolDescriptor deregisterTool(String serviceName, String toolName) {
        Map<String, McpToolDescriptor> tools = serviceRegistry.get(serviceName);
        if (tools == null) {
            return null;
        }
        McpToolDescriptor removed = tools.remove(toolName);
        if (removed != null) {
            log.info("[Nacos MCP] 工具已注销: serviceName={}, toolName={}", serviceName, toolName);
        }
        // 若服务下已无工具，移除该服务条目
        if (tools.isEmpty()) {
            serviceRegistry.remove(serviceName);
        }
        return removed;
    }
}
