package com.contentops.common.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.internal.Json.JsonCodec;

import java.lang.reflect.Type;

/**
 * 宽松 JSON 编解码器 — 替换 LangChain4j 默认 {@code JacksonJsonCodec}。
 *
 * <p>DeepSeek 等国产模型的 JSON 输出经常与 DTO 结构不完全一致（字段嵌套错位、
 * 多余字段等）。默认 Jackson 配置开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 只要模型把字段放错层级就会整体解析失败。本实现关闭该开关，让可识别字段照常绑定、
 * 未知字段静默忽略，显著提高结构化输出的容错率。
 *
 * <p>通过 {@code META-INF/services/dev.langchain4j.spi.json.JsonCodecFactory}
 * 注册为全局 SPI，所有 AiServices 的结构化解析均生效。
 */
public class LenientJsonCodec implements JsonCodec {

    private final ObjectMapper objectMapper;

    public LenientJsonCodec() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        this.objectMapper.findAndRegisterModules();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 关键：忽略未知字段，容忍模型输出与 DTO 结构不一致
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return readWithRepair(extractJson(json), type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(String json, Type type) {
        try {
            return readWithRepair(extractJson(json), objectMapper.constructType(type));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T readWithRepair(String candidate, Class<T> type) throws Exception {
        try {
            return objectMapper.readValue(candidate, type);
        } catch (Exception first) {
            String repaired = repairTruncation(candidate);
            if (repaired != null && !repaired.equals(candidate)) {
                try {
                    return objectMapper.readValue(repaired, type);
                } catch (Exception ignored) {
                    // 修复失败时抛出原始异常，保留可诊断信息
                }
            }
            throw first;
        }
    }

    private <T> T readWithRepair(String candidate, com.fasterxml.jackson.databind.JavaType type) throws Exception {
        try {
            return objectMapper.readValue(candidate, type);
        } catch (Exception first) {
            String repaired = repairTruncation(candidate);
            if (repaired != null && !repaired.equals(candidate)) {
                try {
                    return objectMapper.readValue(repaired, type);
                } catch (Exception ignored) {
                    // 修复失败时抛出原始异常
                }
            }
            throw first;
        }
    }

    /**
     * 模型偶发截断输出（如最后一个右括号丢失）时，按 LIFO 顺序自动补齐缺失的闭合符号。
     * 仅当缺失量很小且补齐后能解析成功时生效。
     */
    private static String repairTruncation(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> stack.push('}');
                case '[' -> stack.push(']');
                case '}', ']' -> {
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.pop();
                }
                default -> {
                    // ignore
                }
            }
        }
        if (stack.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(json);
        while (!stack.isEmpty()) {
            sb.append(stack.pop());
        }
        return sb.toString();
    }

    /**
     * 从模型输出中提取合法 JSON：去掉前言/结尾叙述、markdown 代码块，
     * 并截取到首尾括号平衡的位置（容忍 DeepSeek 在 JSON 前后附加文本）。
     */
    private static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) {
                s = s.substring(firstNl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.strip();
        }
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start > 0) {
            s = s.substring(start);
        }
        return trimToBalanced(s);
    }

    /** 截取到 JSON 结构平衡闭合的位置；不平衡时原样返回交给 Jackson 报错。 */
    private static String trimToBalanced(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth == 0) {
                        return s.substring(0, i + 1);
                    }
                }
                default -> {
                    // ignore
                }
            }
        }
        return s;
    }
}
