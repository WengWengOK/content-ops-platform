package com.contentops.common.mcp;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 工具扫描器。
 *
 * <p>实现 {@link ApplicationListener}，在 Spring 上下文刷新完成后（所有 Bean 初始化完毕）
 * 自动扫描所有 Bean 中的 {@link Tool @Tool} 注解方法，并将找到的工具注册到
 * {@link McpToolRegistry}。
 *
 * <p><b>为什么使用 ApplicationListener 而非 BeanPostProcessor</b>：
 * <ul>
 *   <li>BeanPostProcessor 在 Bean 初始化阶段执行，此时其他 Bean 可能尚未创建，
 *       且过早代理可能影响 Spring 内部基础设施 Bean</li>
 *   <li>ApplicationListener&lt;ContextRefreshedEvent&gt; 在所有 Bean 就绪后触发，
 *       可安全遍历整个容器</li>
 *   <li>通过 {@link AtomicBoolean} 防护防止在父子容器层级中重复扫描</li>
 * </ul>
 *
 * <p><b>扫描逻辑</b>：
 * <ol>
 *   <li>遍历容器中所有 Bean 实例</li>
 *   <li>使用 {@link ClassUtils#getUserClass} 解析实际类（处理 CGLIB 代理）</li>
 *   <li>查找带有 @Tool 注解的 public 方法</li>
 *   <li>为每个 @Tool 方法构建 {@link McpToolDescriptor}（含参数信息，取自 @P 注解）</li>
 *   <li>注册到 {@link McpToolRegistry}</li>
 * </ol>
 *
 * <p>通过 {@code contentops.mcp.enabled=true} 且 {@code contentops.mcp.auto-scan=true} 启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "contentops.mcp.enabled", havingValue = "true")
public class McpToolScanner implements ApplicationListener<ContextRefreshedEvent> {

    private final McpToolRegistry mcpToolRegistry;
    private final McpRegistryProperties mcpRegistryProperties;

    /** 防止在父子容器中重复扫描 */
    private final AtomicBoolean scanned = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 仅在 auto-scan 开启时执行
        if (!mcpRegistryProperties.isAutoScan()) {
            log.info("[MCP Scanner] auto-scan 已关闭，跳过 @Tool 方法扫描");
            return;
        }

        // 防止父子容器重复扫描
        if (!scanned.compareAndSet(false, true)) {
            log.debug("[MCP Scanner] 已扫描过，跳过重复扫描");
            return;
        }

        ApplicationContext context = event.getApplicationContext();
        log.info("[MCP Scanner] 开始扫描 @Tool 方法...");

        int toolCount = 0;
        int beanCount = 0;

        // 遍历容器中所有 Bean
        Map<String, Object> allBeans = context.getBeansOfType(Object.class);
        for (Map.Entry<String, Object> entry : allBeans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();

            // 解析实际类（处理 Spring AOP 代理）
            Class<?> beanClass = ClassUtils.getUserClass(bean);

            // 扫描该类中的 @Tool 方法
            List<McpToolDescriptor> descriptors = scanToolMethods(bean, beanClass);
            if (!descriptors.isEmpty()) {
                beanCount++;
                for (McpToolDescriptor descriptor : descriptors) {
                    mcpToolRegistry.register(descriptor);
                    toolCount++;
                }
                log.debug("[MCP Scanner] Bean '{}' (class={}) 注册了 {} 个工具",
                        beanName, beanClass.getSimpleName(), descriptors.size());
            }
        }

        log.info("[MCP Scanner] 扫描完成: 共扫描 {} 个 Bean，注册 {} 个 @Tool 工具",
                beanCount, toolCount);
    }

    /**
     * 扫描指定类中的所有 @Tool 注解方法，构建工具描述符列表。
     *
     * @param bean      Bean 实例
     * @param beanClass Bean 的实际类（已处理代理）
     * @return 工具描述符列表，若无 @Tool 方法返回空列表
     */
    private List<McpToolDescriptor> scanToolMethods(Object bean, Class<?> beanClass) {
        List<McpToolDescriptor> descriptors = new ArrayList<>();

        // 遍历所有声明的方法（包括继承的 public 方法）
        for (Method method : beanClass.getMethods()) {
            // 跳过非 public 方法和合成方法（如桥接方法）
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) {
                continue;
            }

            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }

            // 构建工具描述符
            McpToolDescriptor descriptor = buildDescriptor(bean, beanClass, method, toolAnnotation);
            descriptors.add(descriptor);
        }

        return descriptors;
    }

    /**
     * 为单个 @Tool 方法构建工具描述符。
     *
     * @param bean           Bean 实例
     * @param beanClass      Bean 的实际类
     * @param method         @Tool 方法
     * @param toolAnnotation @Tool 注解实例
     * @return 工具描述符
     */
    private McpToolDescriptor buildDescriptor(Object bean, Class<?> beanClass,
                                               Method method, Tool toolAnnotation) {
        // @Tool.name() 若非空则使用注解指定名称，否则用 BeanSimpleClassName.methodName
        String annotationName = toolAnnotation.name();
        String toolName = (annotationName != null && !annotationName.isBlank())
                ? annotationName
                : beanClass.getSimpleName() + "." + method.getName();

        // @Tool.value() 返回 String[]，拼接为单个描述字符串
        String[] descParts = toolAnnotation.value();
        String description = (descParts != null && descParts.length > 0)
                ? String.join(" ", descParts)
                : "";

        String returnType = method.getReturnType().getSimpleName();

        // 解析参数信息
        List<McpToolParameter> parameters = new ArrayList<>();
        Parameter[] methodParams = method.getParameters();
        for (int i = 0; i < methodParams.length; i++) {
            Parameter param = methodParams[i];

            String paramName = param.isNamePresent() ? param.getName() : "arg" + i;
            String paramDesc = "";
            boolean required = true;

            // 从 @P 注解获取参数描述和 required 标记
            P pAnnotation = param.getAnnotation(P.class);
            if (pAnnotation != null) {
                paramDesc = pAnnotation.value();
                required = pAnnotation.required();
            }

            McpToolParameter mcpParam = McpToolParameter.builder()
                    .name(paramName)
                    .description(paramDesc)
                    .type(param.getType().getSimpleName())
                    .required(required)
                    .build();

            parameters.add(mcpParam);
        }

        // 确保方法可访问（处理非 public 类的 public 方法）
        try {
            if (!method.canAccess(bean)) {
                method.setAccessible(true);
            }
        } catch (Exception e) {
            // 公开方法通常不需要 setAccessible，忽略安全异常
            log.debug("[MCP Scanner] setAccessible 跳过: {}.{}", beanClass.getSimpleName(), method.getName());
        }

        return McpToolDescriptor.builder()
                .toolName(toolName)
                .description(description)
                .parameters(parameters)
                .returnType(returnType)
                .bean(bean)
                .method(method)
                .build();
    }
}
