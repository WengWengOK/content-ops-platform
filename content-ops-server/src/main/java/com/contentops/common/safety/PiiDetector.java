package com.contentops.common.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII（个人身份信息）检测与脱敏专用组件。
 *
 * <p>使用预编译正则表达式检测中文场景常见的 PII 类型，并对命中内容进行脱敏处理。
 * 脱敏策略统一为「保留首尾、中间用 {@code *} 替代」，具体保留长度因类型而异：
 *
 * <h3>支持检测的 PII 类型</h3>
 * <ul>
 *   <li><b>中国手机号</b>：{@code 1[3-9]\d{9}}，脱敏为 {@code 138****1234}（保留前 3 后 4）</li>
 *   <li><b>身份证号</b>：18 位（末位可为 X），<b>含校验位验证</b>，脱敏为 {@code 110101********1234}（保留前 6 后 4）</li>
 *   <li><b>邮箱地址</b>：标准邮箱，脱敏为 {@code a***@example.com}（保留本地名首字符与完整域名）</li>
 *   <li><b>银行卡号</b>：16-19 位数字（可选 Luhn 校验），脱敏为 {@code 6222********1234}（保留前 4 后 4）</li>
 *   <li><b>IP 地址</b>：IPv4，脱敏为 {@code 192.*.*.*}（保留首段，其余打码）</li>
 *   <li><b>微信号</b>：{@code 微信/VX/WeChat: xxx} 形式，脱敏为 {@code wx***d}（保留首尾各 1-2 字符）</li>
 * </ul>
 *
 * <p>所有正则表达式在类加载时预编译（{@link Pattern#compile}），避免每次调用重复编译带来的性能损耗。
 *
 * @see SafetyProperties.PiiConfig
 */
@Slf4j
@Component
public class PiiDetector {

    // ──────────────── PII 类型枚举 ────────────────

    /** PII 类型。 */
    public enum PiiType {
        /** 中国手机号 */
        PHONE("手机号"),
        /** 身份证号 */
        ID_CARD("身份证号"),
        /** 邮箱地址 */
        EMAIL("邮箱"),
        /** 银行卡号 */
        BANK_CARD("银行卡号"),
        /** IP 地址 */
        IP("IP地址"),
        /** 微信号 */
        WECHAT("微信号");

        private final String label;

        PiiType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // ──────────────── 预编译正则模式 ────────────────

    /** 中国手机号：1[3-9] 开头共 11 位，前后不能紧邻数字（避免从长数字串中误匹配）。 */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");

    /** 身份证号：18 位，前 17 位数字，末位数字或 X/x，前后不能紧邻数字。 */
    private static final Pattern ID_CARD_PATTERN =
            Pattern.compile("(?<![0-9])([1-9]\\d{16})([0-9Xx])(?![0-9Xx])");

    /** 邮箱地址：标准邮箱格式。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    /** 银行卡号：16-19 位数字，前后不能紧邻数字。 */
    private static final Pattern BANK_CARD_PATTERN =
            Pattern.compile("(?<![0-9])\\d{16,19}(?![0-9])");

    /** IPv4 地址：4 段 0-255 数字。 */
    private static final Pattern IP_PATTERN =
            Pattern.compile("(?<![0-9.])((?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3})(?![0-9.])");

    /** 微信号：以「微信/wechat/vx/wx」引导 + 冒号/空格 + 字母开头的 6-20 位 ID。 */
    private static final Pattern WECHAT_PATTERN =
            Pattern.compile("(?i)(?:微信|wechat|vx|wx|v信)[\\s::：]*([a-zA-Z][a-zA-Z0-9_-]{5,19})");

    /** Base64 编码疑似串（用于 PromptInjectionDetector，此处不使用，保留占位说明）。 */

    // ──────────────── 身份证校验位算法常量 ────────────────

    /** 18 位身份证前 17 位各位的加权因子。 */
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验位对照表（按 mod 11 的余数索引）。 */
    private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private final SafetyProperties properties;

    public PiiDetector(SafetyProperties properties) {
        this.properties = properties;
    }

    /**
     * 检测文本中的全部 PII 并返回脱敏后的内容。
     *
     * <p>依据 {@link SafetyProperties.PiiConfig} 的各项开关决定检测哪些类型。
     *
     * @param content 待检测文本，为 null 或空白时原样返回
     * @return PII 检测结果，包含检测到的类型集合、脱敏后内容与命中总数
     */
    public PiiResult detect(String content) {
        if (content == null || content.isBlank()) {
            return new PiiResult(List.of(), content == null ? "" : content, 0, List.of());
        }

        SafetyProperties.PiiConfig config = properties.getPii();
        List<PiiMatch> matches = new ArrayList<>();
        String result = content;

        try {
            if (config.isDetectPhone()) {
                result = scanAndRedact(result, PHONE_PATTERN, PiiType.PHONE,
                        m -> maskMiddle(m, 3, 4, config.getMaskChar()), matches);
            }
            if (config.isDetectIdCard()) {
                result = scanAndRedactIdCard(result, config.getMaskChar(), matches);
            }
            if (config.isDetectEmail()) {
                result = scanAndRedact(result, EMAIL_PATTERN, PiiType.EMAIL,
                        m -> maskEmail(m, config.getMaskChar()), matches);
            }
            if (config.isDetectBankCard()) {
                result = scanAndRedactBankCard(result, config.getMaskChar(), matches);
            }
            if (config.isDetectIp()) {
                result = scanAndRedact(result, IP_PATTERN, PiiType.IP,
                        m -> maskIp(m, config.getMaskChar()), matches);
            }
            if (config.isDetectWechat()) {
                result = scanAndRedactWechat(result, config.getMaskChar(), matches);
            }
        } catch (Exception e) {
            // 降级：检测异常时返回已处理部分，不影响主流程
            log.error("[PiiDetector] PII 检测发生异常，返回已处理内容: {}", e.getMessage(), e);
        }

        List<PiiType> detectedTypes = matches.stream().map(PiiMatch::type).distinct().toList();
        if (!matches.isEmpty()) {
            log.debug("[PiiDetector] 检测到 {} 处 PII，类型: {}", matches.size(), detectedTypes);
        }
        return new PiiResult(detectedTypes, result, matches.size(), List.copyOf(matches));
    }

    /**
     * 仅检测是否包含 PII，不进行脱敏（用于输出护栏的敏感信息泄露检查）。
     *
     * @param content 待检测文本
     * @return true 表示包含至少一处 PII
     */
    public boolean containsPii(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        SafetyProperties.PiiConfig config = properties.getPii();
        return (config.isDetectPhone() && PHONE_PATTERN.matcher(content).find())
                || (config.isDetectIdCard() && findValidIdCard(content) != null)
                || (config.isDetectEmail() && EMAIL_PATTERN.matcher(content).find())
                || (config.isDetectBankCard() && findValidBankCard(content))
                || (config.isDetectIp() && IP_PATTERN.matcher(content).find())
                || (config.isDetectWechat() && WECHAT_PATTERN.matcher(content).find());
    }

    // ──────────────── 通用扫描与脱敏 ────────────────

    /**
     * 通用扫描：遍历模式匹配，逐个脱敏并记录命中。
     *
     * @param text      原始文本
     * @param pattern   预编译正则
     * @param type      PII 类型
     * @param redactor  脱敏函数
     * @param matches   命中记录收集器
     * @return 脱敏后的文本
     */
    private String scanAndRedact(String text, Pattern pattern, PiiType type,
                                 java.util.function.Function<String, String> redactor,
                                 List<PiiMatch> matches) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            String redacted = redactor.apply(matched);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(redacted));
            matches.add(new PiiMatch(type, matched, redacted, matcher.start(), matcher.end()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ──────────────── 身份证号检测（含校验位验证） ────────────────

    /**
     * 扫描身份证号并脱敏：仅对通过校验位验证的身份证号进行脱敏，避免误伤普通 18 位数字。
     */
    private String scanAndRedactIdCard(String text, String maskChar, List<PiiMatch> matches) {
        Matcher matcher = ID_CARD_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String full = matcher.group();
            if (!validateIdCard(full)) {
                // 未通过校验，不脱敏，原样保留
                matcher.appendReplacement(sb, Matcher.quoteReplacement(full));
                continue;
            }
            String redacted = maskMiddle(full, 6, 4, maskChar);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(redacted));
            matches.add(new PiiMatch(PiiType.ID_CARD, full, redacted, matcher.start(), matcher.end()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 验证 18 位身份证号校验位是否正确。
     *
     * @param idCard 18 位身份证号（末位可为 X）
     * @return true 表示校验通过
     */
    public static boolean validateIdCard(String idCard) {
        if (idCard == null || idCard.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = idCard.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        char expected = ID_CHECK_CODES[sum % 11];
        char actual = Character.toUpperCase(idCard.charAt(17));
        return expected == actual;
    }

    /**
     * 在文本中查找首个通过校验的身份证号。
     *
     * @return 首个有效身份证号字符串，未找到返回 null
     */
    private String findValidIdCard(String content) {
        Matcher matcher = ID_CARD_PATTERN.matcher(content);
        while (matcher.find()) {
            if (validateIdCard(matcher.group())) {
                return matcher.group();
            }
        }
        return null;
    }

    // ──────────────── 银行卡号检测（含 Luhn 校验） ────────────────

    /**
     * 扫描银行卡号并脱敏：对通过 Luhn 校验的卡号脱敏，避免误伤普通长数字。
     */
    private String scanAndRedactBankCard(String text, String maskChar, List<PiiMatch> matches) {
        Matcher matcher = BANK_CARD_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            if (!luhnCheck(matched)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matched));
                continue;
            }
            String redacted = maskMiddle(matched, 4, 4, maskChar);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(redacted));
            matches.add(new PiiMatch(PiiType.BANK_CARD, matched, redacted, matcher.start(), matcher.end()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 判断文本中是否存在通过 Luhn 校验的银行卡号。
     */
    private boolean findValidBankCard(String content) {
        Matcher matcher = BANK_CARD_PATTERN.matcher(content);
        while (matcher.find()) {
            if (luhnCheck(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Luhn 算法校验银行卡号合法性。
     *
     * @param number 纯数字卡号
     * @return true 表示通过 Luhn 校验
     */
    public static boolean luhnCheck(String number) {
        if (number == null || number.length() < 16 || number.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            char c = number.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int n = c - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    // ──────────────── 微信号检测 ────────────────

    /**
     * 扫描微信号并脱敏：仅脱敏捕获组中的 ID 部分，引导词保留。
     */
    private String scanAndRedactWechat(String text, String maskChar, List<PiiMatch> matches) {
        Matcher matcher = WECHAT_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group(1);
            String redactedId = maskMiddle(id, 1, 1, maskChar);
            String redactedFull = matcher.group().replace(id, redactedId);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(redactedFull));
            matches.add(new PiiMatch(PiiType.WECHAT, id, redactedId, matcher.start(1), matcher.end(1)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ──────────────── 脱敏工具方法 ────────────────

    /**
     * 通用脱敏：保留首 {@code keepHead} 位与末 {@code keepTail} 位，中间用掩码字符填充。
     *
     * @param text     原始文本
     * @param keepHead 保留头部字符数
     * @param keepTail 保留尾部字符数
     * @param maskChar 掩码字符
     * @return 脱敏后的文本
     */
    private String maskMiddle(String text, int keepHead, int keepTail, String maskChar) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int len = text.length();
        if (len <= keepHead + keepTail) {
            // 长度不足以同时保留首尾，全部打码
            return maskChar.repeat(len);
        }
        int maskLen = len - keepHead - keepTail;
        return text.substring(0, keepHead)
                + maskChar.repeat(maskLen)
                + text.substring(len - keepTail);
    }

    /**
     * 邮箱脱敏：保留本地名首字符 + 掩码 + 完整域名。
     * <p>例：{@code john.doe@example.com} → {@code j***@example.com}
     */
    private String maskEmail(String email, String maskChar) {
        int atIdx = email.indexOf('@');
        if (atIdx <= 0) {
            return maskMiddle(email, 1, 1, maskChar);
        }
        String local = email.substring(0, atIdx);
        String domain = email.substring(atIdx);
        String maskedLocal = local.charAt(0) + maskChar.repeat(Math.max(1, local.length() - 1));
        return maskedLocal + domain;
    }

    /**
     * IP 地址脱敏：保留首段，其余段用掩码替换。
     * <p>例：{@code 192.168.1.1} → {@code 192.*.*.*}
     */
    private String maskIp(String ip, String maskChar) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return maskMiddle(ip, 1, 1, maskChar);
        }
        return parts[0] + "." + maskChar + "." + maskChar + "." + maskChar;
    }

    // ──────────────── 结果类型 ────────────────

    /**
     * PII 检测结果。
     *
     * @param detectedTypes   检测到的 PII 类型（去重）
     * @param redactedContent 脱敏后的内容
     * @param detectionCount  命中总数
     * @param matches         各命中详情列表
     */
    public record PiiResult(
            List<PiiType> detectedTypes,
            String redactedContent,
            int detectionCount,
            List<PiiMatch> matches
    ) {
        public PiiResult {
            detectedTypes = detectedTypes == null ? List.of() : List.copyOf(detectedTypes);
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        /** 是否检测到 PII。 */
        public boolean hasPii() {
            return detectionCount > 0;
        }

        /** 按类型分组的命中计数。 */
        public Map<PiiType, Long> countByType() {
            return matches.stream()
                    .collect(java.util.stream.Collectors.groupingBy(PiiMatch::type,
                            LinkedHashMap::new, java.util.stream.Collectors.counting()));
        }
    }

    /**
     * 单个 PII 命中详情。
     *
     * @param type     PII 类型
     * @param value    命中的原始文本
     * @param redacted 脱敏后的文本
     * @param start    起始偏移量
     * @param end      结束偏移量
     */
    public record PiiMatch(
            PiiType type,
            String value,
            String redacted,
            int start,
            int end
    ) {
    }
}
