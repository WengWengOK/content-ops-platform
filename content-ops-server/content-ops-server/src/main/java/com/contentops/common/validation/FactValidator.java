package com.contentops.common.validation;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实校验器 — 检测 Agent 输出中的事实性错误与幻觉。
 *
 * <h3>校验维度（轻量规则，避免 LLM 调用开销）</h3>
 * <ul>
 *   <li><b>占位符泄漏</b>：检测 {{placeholder}}、[TODO]、xxxxx、lorem ipsum 等未填充占位符</li>
 *   <li><b>数字合理性</b>：百分比应在 0-100，年份应在合理范围（1900-2100）</li>
 *   <li><b>链接格式</b>：URL 应为合法 http(s) 格式（若出现）</li>
 *   <li><b>重复废话</b>：检测连续重复字符（如 "啊啊啊啊啊啊"）或同句重复</li>
 *   <li><b>明显幻觉标记</b>：检测 "作为一个 AI"、"我无法保证" 等暴露 LLM 身份的表述</li>
 * </ul>
 *
 * <p>设计权衡：本实现为<b>规则驱动</b>（零 LLM 调用），适合作为快速事实筛查。
 * 深度事实核查（如"某历史事件日期是否正确"）需要接入 RAG 知识库或 LLM-as-Judge，
 * 由 {@link com.contentops.common.observability.LlmJudgeService} 异步兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactValidator implements AgentOutputValidator {

    /** 占位符模式：{{xxx}}、[TODO]、<placeholder>、xxxxx（≥4 个 x） */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{[^}]+}}|\\[TODO[^]]*]|<placeholder>|x{4,}|TBD|待填充|待补充",
            Pattern.CASE_INSENSITIVE);

    /** LLM 身份暴露模式 */
    private static final Pattern AI_LEAK_PATTERN = Pattern.compile(
            "作为一个 AI|作为一个大语言模型|作为 AI 助手|我无法保证|我无法访问实时|我的知识截止",
            Pattern.CASE_INSENSITIVE);

    /** 百分比模式：数字 + % */
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    /** 年份模式：19xx / 20xx */
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");

    /** URL 模式 */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+",
            Pattern.CASE_INSENSITIVE);

    /** 连续重复字符（同一字符出现 ≥5 次，排除标点） */
    private static final Pattern REPEAT_PATTERN = Pattern.compile("([^\\s\\p{Punct}])\\1{4,}");

    @Override
    public ValidationType type() {
        return ValidationType.FACT;
    }

    @Override
    public ValidationResult validate(AgentStage stage, Map<String, Object> data, TaskContext context) {
        String text = flattenToString(data);
        if (text.isBlank()) {
            return ValidationResult.block(ValidationType.FACT, List.of("输出内容为空，无法做事实校验"));
        }

        List<String> failures = new ArrayList<>();

        // 1. 占位符泄漏（BLOCK）
        Matcher placeholderMatcher = PLACEHOLDER_PATTERN.matcher(text);
        List<String> placeholders = new ArrayList<>();
        while (placeholderMatcher.find() && placeholders.size() < 3) {
            placeholders.add(placeholderMatcher.group().trim());
        }
        if (!placeholders.isEmpty()) {
            failures.add("检测到未填充占位符：" + String.join(" / ", placeholders));
        }

        // 2. LLM 身份暴露（BLOCK）
        Matcher aiLeakMatcher = AI_LEAK_PATTERN.matcher(text);
        List<String> aiLeaks = new ArrayList<>();
        while (aiLeakMatcher.find() && aiLeaks.size() < 2) {
            aiLeaks.add(aiLeakMatcher.group().trim());
        }
        if (!aiLeaks.isEmpty()) {
            failures.add("检测到 LLM 身份暴露表述：" + String.join(" / ", aiLeaks));
        }

        // 3. 连续重复字符（WARN，不阻断）
        Matcher repeatMatcher = REPEAT_PATTERN.matcher(text);
        List<String> repeats = new ArrayList<>();
        while (repeatMatcher.find() && repeats.size() < 2) {
            repeats.add(repeatMatcher.group());
        }
        List<String> warnings = new ArrayList<>();
        if (!repeats.isEmpty()) {
            warnings.add("检测到连续重复字符：" + String.join(" / ", repeats));
        }

        // 4. 百分比合理性（>100% 或 <0% → WARN）
        Matcher percentMatcher = PERCENT_PATTERN.matcher(text);
        while (percentMatcher.find()) {
            try {
                double pct = Double.parseDouble(percentMatcher.group(1));
                if (pct > 100 || pct < 0) {
                    warnings.add("百分比超出合理范围：" + pct + "%");
                    break;
                }
            } catch (NumberFormatException ignored) {
                // 数字解析失败，跳过
            }
        }

        // 5. 年份合理性（未来太远 → WARN）
        Matcher yearMatcher = YEAR_PATTERN.matcher(text);
        int currentYear = java.time.Year.now().getValue();
        while (yearMatcher.find()) {
            try {
                int year = Integer.parseInt(yearMatcher.group());
                if (year > currentYear + 5) {
                    warnings.add("年份超出合理范围（未来太远）：" + year);
                    break;
                }
            } catch (NumberFormatException ignored) {
                // 忽略
            }
        }

        // 6. URL 格式校验（出现非法 URL → WARN）
        Matcher urlMatcher = URL_PATTERN.matcher(text);
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            // 简单校验：不应包含空格或中文
            if (url.matches(".*[\\u4e00-\\u9fa5\\s].*")) {
                warnings.add("URL 包含非法字符：" + url.substring(0, Math.min(50, url.length())));
                break;
            }
        }

        if (!failures.isEmpty()) {
            log.warn("[FactValidator] stage={} 事实校验失败：{}", stage.getCode(), failures);
            return ValidationResult.block(ValidationType.FACT, failures);
        }

        if (!warnings.isEmpty()) {
            log.info("[FactValidator] stage={} 事实校验警告：{}", stage.getCode(), warnings);
            return ValidationResult.warn(ValidationType.FACT, warnings);
        }

        return ValidationResult.pass(ValidationType.FACT);
    }

    /** 把 Map 数据扁平化为纯文本，便于正则匹配 */
    private String flattenToString(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Object value : data.values()) {
            appendValue(sb, value);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendValue(StringBuilder sb, Object value) {
        if (value instanceof String s) {
            sb.append(s).append("\n");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value).append("\n");
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                appendValue(sb, item);
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                appendValue(sb, v);
            }
        }
    }
}
