package com.contentops.common.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册中心。
 *
 * <p>Spring @Component，作为 MCP 工具的中央注册表。所有通过 {@link McpToolScanner}
 * 自动扫描或 {@link PlatformToolIntegration} 手动注册的工具都会存入内部 registry Map。
 *
 * <p>提供三个核心方法：
 * <ul>
 *   <li>{@link #listTools()} — 返回所有已注册工具的描述符</li>
 *   <li>{@link #getTool(String)} — 按名称获取单个工具描述符</li>
 *   <li>{@link #executeTool(String, Map)} — 按名称执行工具，参数通过 Map 传入，
 *       使用反射调用 @Tool 方法。执行时若 {@link McpToolInvocationInterceptor} 可用，
 *       则通过拦截器包装调用以记录日志和指标</li>
 * </ul>
 *
 * <p>参数解析：通过 Java 反射获取方法参数名（依赖 -parameters 编译标志，
 * Spring Boot 默认启用），从 args Map 中按名称取值，并使用 Jackson ObjectMapper
 * 进行类型转换。
 */
@Slf4j
@Component
public class McpToolRegistry {

    /** 工具注册表，key = toolName，value = 工具描述符 */
    private final Map<String, McpToolDescriptor> registry = new ConcurrentHashMap<>();

    /** Jackson ObjectMapper，用于参数类型转换 */
    private final ObjectMapper objectMapper;

    /** 工具调用拦截器（可选，仅在 contentops.mcp.enabled=true 时存在） */
    @Autowired(required = false)
    private McpToolInvocationInterceptor interceptor;

    public McpToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        log.info("[MCP] McpToolRegistry 已初始化");
    }

    /**
     * 注册一个工具描述符到注册中心。
     *
     * <p>若同名工具已存在，将覆盖旧值并记录警告日志。
     *
     * @param descriptor 工具描述符
     */
    public void register(McpToolDescriptor descriptor) {
        if (descriptor == null || descriptor.getToolName() == null) {
            log.warn("[MCP] 跳过无效的工具描述符: {}", descriptor);
            return;
        }
        McpToolDescriptor existing = registry.put(descriptor.getToolName(), descriptor);
        if (existing != null) {
            log.warn("[MCP] 工具名称冲突，已覆盖旧注册: {} (旧: {}.{}, 新: {}.{})",
                    descriptor.getToolName(),
                    existing.getBean() != null ? existing.getBean().getClass().getSimpleName() : "?",
                    existing.getMethod() != null ? existing.getMethod().getName() : "?",
                    descriptor.getBean() != null ? descriptor.getBean().getClass().getSimpleName() : "?",
                    descriptor.getMethod() != null ? descriptor.getMethod().getName() : "?");
        } else {
            log.info("[MCP] 工具已注册: {} | 描述: {} | 参数数: {}",
                    descriptor.getToolName(),
                    truncate(descriptor.getDescription(), 80),
                    descriptor.getParameters() != null ? descriptor.getParameters().size() : 0);
        }
    }

    /**
     * 返回所有已注册工具的描述符集合。
     *
     * @return 不可变的工具描述符集合
     */
    public Collection<McpToolDescriptor> listTools() {
        return registry.values();
    }

    /**
     * 按名称获取工具描述符。
     *
     * @param name 工具名称
     * @return 工具描述符，若不存在返回 null
     */
    public McpToolDescriptor getTool(String name) {
        return registry.get(name);
    }

    /**
     * 按名称执行工具。
     *
     * <p>参数解析流程：
     * <ol>
     *   <li>通过反射获取方法参数名</li>
     *   <li>从 args Map 中按参数名取值</li>
     *   <li>使用 Jackson ObjectMapper 进行类型转换</li>
     *   <li>若拦截器可用，通过拦截器包装调用</li>
     * </ol>
     *
     * @param name 工具名称
     * @param args 参数 Map（key = 参数名，value = 参数值）
     * @return 工具方法的返回值
     * @throws Exception 工具不存在、参数解析失败或方法执行异常
     */
    public Object executeTool(String name, Map<String, Object> args) throws Exception {
        McpToolDescriptor descriptor = registry.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("工具不存在: " + name);
        }

        Object[] resolvedArgs = resolveArguments(descriptor, args);

        // 若拦截器可用，通过拦截器包装调用以记录日志和指标
        if (interceptor != null) {
            try {
                return interceptor.intercept(descriptor.getBean(), descriptor.getMethod(), resolvedArgs);
            } catch (Throwable t) {
                // interceptor.intercept 声明 throws Throwable，需转换为 Exception
                if (t instanceof Exception e) {
                    throw e;
                }
                if (t instanceof Error e) {
                    throw e;
                }
                throw new RuntimeException("MCP 工具执行异常: " + name, t);
            }
        }

        return descriptor.getMethod().invoke(descriptor.getBean(), resolvedArgs);
    }

    /**
     * 解析方法参数：从 args Map 中按参数名取值并进行类型转换。
     *
     * @param descriptor 工具描述符
     * @param args       参数 Map
     * @return 参数值数组，顺序与方法参数一致
     */
    private Object[] resolveArguments(McpToolDescriptor descriptor, Map<String, Object> args) {
        Method method = descriptor.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] resolvedArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            String paramName = getParameterName(param, i);
            Object value = args != null ? args.get(paramName) : null;

            // 如果按参数名找不到，尝试用 @P 注解的描述匹配（兜底）
            if (value == null && args != null) {
                value = args.get("arg" + i);
            }

            // 原生类型默认值处理
            if (value == null && param.getType().isPrimitive()) {
                value = getPrimitiveDefault(param.getType());
            }

            // 类型转换
            if (value != null && !param.getType().isAssignableFrom(value.getClass())) {
                try {
                    value = objectMapper.convertValue(value, param.getType());
                } catch (Exception e) {
                    log.warn("[MCP] 参数类型转换失败: tool={}, param={}, value={}, targetType={}, error={}",
                            descriptor.getToolName(), paramName, value, param.getType().getSimpleName(),
                            e.getMessage());
                }
            }

            resolvedArgs[i] = value;
        }

        return resolvedArgs;
    }

    /**
     * 获取参数名称：优先使用反射参数名，兜底使用 "arg" + index。
     */
    private String getParameterName(Parameter param, int index) {
        if (param.isNamePresent()) {
            return param.getName();
        }
        return "arg" + index;
    }

    /**
     * 获取原生类型的默认值。
     */
    private Object getPrimitiveDefault(Class<?> primitiveType) {
        if (primitiveType == boolean.class) return false;
        if (primitiveType == byte.class) return (byte) 0;
        if (primitiveType == short.class) return (short) 0;
        if (primitiveType == int.class) return 0;
        if (primitiveType == long.class) return 0L;
        if (primitiveType == float.class) return 0.0f;
        if (primitiveType == double.class) return 0.0d;
        if (primitiveType == char.class) return '\0';
        return null;
    }

    /**
     * 截断字符串用于日志输出。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
