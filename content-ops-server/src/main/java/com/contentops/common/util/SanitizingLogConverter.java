package com.contentops.common.util;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback 消息转换器 — 在日志输出前对敏感 token 进行脱敏。
 *
 * <p>在 logback-spring.xml 中使用：
 * <pre>
 * {@code
 * <conversionRule conversionWord="msgSanitized"
 *                 converterClass="com.contentops.common.util.SanitizingLogConverter"/>
 * }
 * </pre>
 *
 * <p>然后使用 {@code %msgSanitized} 替代 {@code %msg} 即可自动脱敏。
 */
public class SanitizingLogConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String original = super.convert(event);
        return TokenLogSanitizer.sanitize(original);
    }
}
