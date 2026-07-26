package com.contentops.common.mcp;

import com.contentops.common.metrics.TokenMetricsService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

/**
 * MCP 工具调用拦截器。
 *
 * <p>实现 AOP Alliance {@link MethodInterceptor} 接口，对所有 @Tool 方法调用进行环绕拦截，
 * 记录调用日志、耗时、参数，并将调用指标发送到 {@link TokenMetricsService}。
 *
 * <p><b>设计说明</b>：
 * <ul>
 *   <li>本拦截器不通过 BeanPostProcessor 代理 @Tool Bean，避免 CGLIB 代理导致
 *       LangChain4j 的 AiServices 工具扫描失效（CGLIB 子类方法不保留 @Tool 注解）</li>
 *   <li>拦截器由 {@link McpToolRegistry#executeTool} 在执行工具时主动调用，
 *       覆盖所有经由 MCP 端点发起的工具调用</li>
 *   <li>{@link #intercept(Object, Method, Object[])} 方法封装了
 *       {@link MethodInvocation} 的构造，供注册中心便捷调用</li>
 * </ul>
 *
 * <p><b>指标记录</b>：
 * <ul>
 *   <li>调用次数：{@code contentops.llm.calls.total}，tag: agentStage=mcp-tool, status=success/failure</li>
 *   <li>调用延迟：{@code contentops.agent.duration}，tag: agentStage=mcp-tool</li>
 * </ul>
 *
 * <p>通过 {@code contentops.mcp.enabled=true} 启用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "contentops.mcp.enabled", havingValue = "true")
public class McpToolInvocationInterceptor implements MethodInterceptor {

    /** MCP 工具调用在指标中的 agentStage 标签值 */
    private static final String MCP_TOOL_STAGE = "mcp-tool";

    private final TokenMetricsService tokenMetricsService;

    @Autowired
    public McpToolInvocationInterceptor(TokenMetricsService tokenMetricsService) {
        this.tokenMetricsService = tokenMetricsService;
        log.info("[MCP] McpToolInvocationInterceptor 已初始化，将拦截所有 @Tool 方法调用");
    }

    /**
     * AOP Alliance 环绕拦截入口。
     *
     * <p>检查目标方法是否带有 @Tool 注解：
     * <ul>
     *   <li>是 — 记录日志、计时、发送指标，然后执行原方法</li>
     *   <li>否 — 直接执行原方法，不做任何拦截处理</li>
     * </ul>
     *
     * @param invocation 方法调用上下文
     * @return 原方法的返回值
     * @throws Throwable 原方法抛出的异常
     */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // 仅拦截带有 @Tool 注解的方法
        if (!method.isAnnotationPresent(Tool.class)) {
            return invocation.proceed();
        }

        String toolName = buildToolName(method);
        Object[] arguments = invocation.getArguments();
        long startNanos = System.nanoTime();

        log.info("[MCP Interceptor] 工具调用开始: {} | 参数: {}", toolName, formatArguments(arguments));

        try {
            Object result = invocation.proceed();
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            log.info("[MCP Interceptor] 工具调用完成: {} | 耗时: {}ms | 返回类型: {}",
                    toolName, durationMs, result != null ? result.getClass().getSimpleName() : "void");

            // 记录成功指标
            tokenMetricsService.recordAgentCall(MCP_TOOL_STAGE, true);
            tokenMetricsService.recordAgentDuration(MCP_TOOL_STAGE, Duration.ofMillis(durationMs));

            return result;
        } catch (Throwable e) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            log.error("[MCP Interceptor] 工具调用失败: {} | 耗时: {}ms | 错误: {}",
                    toolName, durationMs, e.getMessage(), e);

            // 记录失败指标
            tokenMetricsService.recordAgentCall(MCP_TOOL_STAGE, false);
            tokenMetricsService.recordAgentDuration(MCP_TOOL_STAGE, Duration.ofMillis(durationMs));

            throw e;
        }
    }

    /**
     * 便捷拦截入口，供 {@link McpToolRegistry#executeTool} 调用。
     *
     * <p>将目标 Bean、方法和参数封装为 {@link MethodInvocation}，然后委托给
     * {@link #invoke(MethodInvocation)} 执行拦截逻辑。
     *
     * @param target 目标 Bean 实例
     * @param method 要调用的 @Tool 方法
     * @param args   方法参数数组
     * @return 方法返回值
     * @throws Throwable 方法执行异常
     */
    public Object intercept(Object target, Method method, Object[] args) throws Throwable {
        MethodInvocation invocation = new ReflectiveMethodInvocation(target, method, args);
        return invoke(invocation);
    }

    /**
     * 构建工具名称：BeanSimpleClassName.methodName。
     */
    private String buildToolName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /**
     * 格式化参数数组用于日志输出，截断过长的字符串参数。
     */
    private String formatArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return "[]";
        }
        String[] parts = new String[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i] == null) {
                parts[i] = "null";
            } else {
                String str = arguments[i].toString();
                if (str.length() > 200) {
                    parts[i] = str.substring(0, 200) + "...(" + str.length() + " chars)";
                } else {
                    parts[i] = str;
                }
            }
        }
        return Arrays.toString(parts);
    }

    /**
     * 基于反射的简单 MethodInvocation 实现。
     *
     * <p>封装目标 Bean、方法和参数，{@link #proceed()} 通过反射调用原方法。
     * 仅供 {@link McpToolInvocationInterceptor#intercept} 内部使用。
     */
    private static class ReflectiveMethodInvocation implements MethodInvocation {

        private final Object target;
        private final Method method;
        private final Object[] arguments;

        ReflectiveMethodInvocation(Object target, Method method, Object[] arguments) {
            this.target = target;
            this.method = method;
            this.arguments = arguments != null ? arguments : new Object[0];
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArguments() {
            return arguments;
        }

        @Override
        public Object proceed() throws Throwable {
            return method.invoke(target, arguments);
        }

        @Override
        public Object getThis() {
            return target;
        }

        @Override
        public AccessibleObject getStaticPart() {
            return method;
        }
    }
}
