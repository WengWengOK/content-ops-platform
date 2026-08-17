package com.contentops.common.metrics;

/**
 * Token 估算工具（P1: 弹性与可观测性）。
 *
 * <p>LangChain4j {@code @AiService} 接口不直接暴露 token 计数，
 * 此工具基于文本长度进行近似估算，用于异步任务消费者的指标记录。
 *
 * <p>估算规则：中文约 1.5 字符/token，英文约 4 字符/token，混合取均值 ~3 字符/token。
 */
public final class TokenEstimator {

    /** 平均每 token 对应的字符数（中英混合估算） */
    private static final double CHARS_PER_TOKEN = 3.0;

    private TokenEstimator() {
    }

    /**
     * 估算文本的 token 数。
     *
     * @param text 输入文本，为 null 时返回 0
     * @return 估算的 token 数
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 估算多个对象的 token 总数（每个对象调用 toString() 后估算）。
     *
     * @param objects 多个输入对象（String、DTO 等）
     * @return 估算的 token 总数
     */
    public static int estimate(Object... objects) {
        int total = 0;
        for (Object obj : objects) {
            if (obj == null) {
                continue;
            }
            total += estimate(String.valueOf(obj));
        }
        return total;
    }
}
