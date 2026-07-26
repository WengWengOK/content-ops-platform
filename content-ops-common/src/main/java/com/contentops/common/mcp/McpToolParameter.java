package com.contentops.common.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具参数描述符。
 *
 * <p>描述单个工具参数的元信息，用于 MCP 协议中的工具自描述和能力发现。
 * 每个 @Tool 方法的参数会被解析为一个 McpToolParameter。
 *
 * @param name        参数名称（对应 Java 方法参数名）
 * @param description 参数描述（取自 @P 注解的 value，若无注解则为空字符串）
 * @param type        参数类型的简单类名（如 "String"、"int"、"List"）
 * @param required    是否必填（非 Optional 类型且非原生类型默认为 true）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolParameter {

    /** 参数名称 */
    private String name;

    /** 参数描述 */
    private String description;

    /** 参数类型（简单类名） */
    private String type;

    /** 是否必填 */
    private boolean required;
}
