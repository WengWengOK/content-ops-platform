package com.contentops.common.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Method;
import java.util.List;

/**
 * MCP 工具描述符。
 *
 * <p>封装一个 @Tool 方法的完整元信息，用于 MCP 注册中心的工具管理、
 * 工具发现和工具执行。每个描述符对应一个可被 MCP 客户端调用的工具。
 *
 * <p>序列化时通过 {@link JsonIgnoreProperties} 排除 {@code bean} 和 {@code method}
 * 字段，避免将 Spring Bean 实例和反射 Method 对象暴露给 MCP 客户端。
 *
 * @param toolName    工具名称（格式：BeanSimpleClassName.methodName，保证全局唯一）
 * @param description 工具描述（取自 @Tool 注解的 value）
 * @param parameters  参数列表
 * @param returnType  返回类型的简单类名
 * @param bean        工具所在的 Spring Bean 实例（不序列化）
 * @param method      工具对应的反射 Method 对象（不序列化）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"bean", "method"})
public class McpToolDescriptor {

    /** 工具名称（全局唯一） */
    private String toolName;

    /** 工具描述 */
    private String description;

    /** 参数列表 */
    private List<McpToolParameter> parameters;

    /** 返回类型（简单类名） */
    private String returnType;

    /** 工具所在的 Spring Bean 实例（运行时使用，不序列化） */
    private Object bean;

    /** 工具对应的反射 Method 对象（运行时使用，不序列化） */
    private Method method;
}
