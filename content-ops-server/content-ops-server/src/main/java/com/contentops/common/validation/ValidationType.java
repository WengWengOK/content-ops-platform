package com.contentops.common.validation;

/**
 * 校验类型枚举 — 对应三类校验维度。
 *
 * <p>每个类型代表 Agent 输出校验的一个独立维度，{@link AgentOutputValidator}
 * 实现类按类型注册到 {@link ValidationOrchestrator}。
 *
 * <ul>
 *   <li>{@link #FORMAT} — 格式校验：字段齐全性、类型正确性、必填字段</li>
 *   <li>{@link #FACT} — 事实校验：事实性核查、幻觉检测、数字/日期/链接合理性</li>
 *   <li>{@link #CONSISTENCY} — 一致性校验：跨阶段对齐（选题↔内容、内容↔配图、内容↔发布）</li>
 * </ul>
 */
public enum ValidationType {
    /** 格式校验：schema/字段/类型 */
    FORMAT,
    /** 事实校验：幻觉/数字/日期/链接 */
    FACT,
    /** 一致性校验：跨阶段对齐 */
    CONSISTENCY
}
