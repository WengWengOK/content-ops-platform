package com.contentops.common.finetune;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 微调数据集封装（record 类型）。
 *
 * <p>统一封装三种主流微调数据格式，支持数据集的创建、验证、序列化与自动生成。
 * 作为 {@link ModelFineTuneManager} 的核心数据载体，在微调任务编排过程中传递训练数据。
 *
 * <h3>支持的数据格式</h3>
 * <ul>
 *   <li><b>Instruction 格式</b>（{@link Format#INSTRUCTION}）：指令微调，
 *       包含 instruction（指令）、input（可选输入）、output（期望输出）</li>
 *   <li><b>Chat 格式</b>（{@link Format#CHAT}）：对话微调，
 *       包含 messages 数组（role + content 的多轮对话）</li>
 *   <li><b>Preference 格式</b>（{@link Format#PREFERENCE}）：偏好对齐（DPO），
 *       包含 prompt、chosen（偏好回答）、rejected（拒绝回答）</li>
 * </ul>
 *
 * <h3>验证能力</h3>
 * <ul>
 *   <li>格式校验：确保每条样本符合所选格式的字段要求</li>
 *   <li>长度统计：输出每条样本的字符/token 估算、最大/最小/平均长度</li>
 *   <li>去重检查：基于内容哈希检测重复样本</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建 Instruction 格式数据集
 * FineTuneDataset dataset = FineTuneDataset.of(
 *     "content-ops-instructions",
 *     FineTuneDataset.Format.INSTRUCTION,
 *     List.of(
 *         new FineTuneDataset.InstructionSample(
 *             "为一篇关于AI的文章生成标题",
 *             "文章主题：大语言模型微调",
 *             "大语言模型微调：从LoRA到全参数优化的实践指南"
 *         )
 *     )
 * );
 *
 * // 验证数据集
 * FineTuneDataset.ValidationResult validation = dataset.validate();
 * if (!validation.valid()) {
 *     log.warn("数据集验证失败: {}", validation.errors());
 * }
 *
 * // 导出为 JSONL
 * String jsonl = dataset.toJsonl();
 * }</pre>
 *
 * @see ModelFineTuneManager
 */
@Slf4j
public record FineTuneDataset(
        /** 数据集名称（唯一标识） */
        String name,
        /** 数据集描述 */
        String description,
        /** 数据格式 */
        Format format,
        /** 样本列表 */
        List<DatasetSample> samples,
        /** 元数据（来源、标签、版本等） */
        Map<String, Object> metadata,
        /** 创建时间 */
        LocalDateTime createdAt
) {

    /** Jackson ObjectMapper，用于 JSONL 序列化 */
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    // ════════════════════════════════════════════════════════════════
    // 紧凑构造器：参数校验与默认值填充
    // ════════════════════════════════════════════════════════════════

    /**
     * 紧凑构造器，对入参进行非空校验并填充默认值。
     */
    public FineTuneDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        if (format == null) {
            throw new IllegalArgumentException("数据格式不能为空");
        }
        samples = samples == null ? List.of() : List.copyOf(samples);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        description = description == null ? "" : description;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    // ════════════════════════════════════════════════════════════════
    // 数据格式枚举
    // ════════════════════════════════════════════════════════════════

    /**
     * 微调数据格式枚举。
     */
    public enum Format {
        /** 指令格式：instruction / input / output */
        INSTRUCTION,
        /** 对话格式：messages 数组（role + content） */
        CHAT,
        /** 偏好格式：prompt / chosen / rejected（用于 DPO） */
        PREFERENCE
    }

    // ════════════════════════════════════════════════════════════════
    // 样本类型（sealed 接口 + record 实现）
    // ════════════════════════════════════════════════════════════════

    /**
     * 数据集样本 sealed 接口，三种格式各对应一个 record 实现。
     *
     * <p>使用 sealed 接口确保样本类型穷举可控，便于 switch 模式匹配。
     */
    public sealed interface DatasetSample
            permits InstructionSample, ChatSample, PreferenceSample {

        /**
         * 将样本序列化为 JSONL 单行（一个 JSON 对象）。
         *
         * @return JSON 字符串
         */
        String toJsonLine();

        /**
         * 获取样本的文本总长度（用于长度统计）。
         *
         * @return 字符数
         */
        int textLength();

        /**
         * 获取样本的内容哈希（用于去重）。
         *
         * @return 哈希字符串
         */
        String contentHash();
    }

    /**
     * Instruction 格式样本。
     *
     * @param instruction 指令描述（必填）
     * @param input       输入上下文（可选，可为空字符串）
     * @param output      期望输出（必填）
     */
    public record InstructionSample(
            String instruction,
            String input,
            String output
    ) implements DatasetSample {

        public InstructionSample {
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("instruction 不能为空");
            }
            input = input == null ? "" : input;
            if (output == null || output.isBlank()) {
                throw new IllegalArgumentException("output 不能为空");
            }
        }

        @Override
        public String toJsonLine() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("instruction", instruction);
            map.put("input", input);
            map.put("output", output);
            return writeJson(map);
        }

        @Override
        public int textLength() {
            return instruction.length() + input.length() + output.length();
        }

        @Override
        public String contentHash() {
            return sha1(instruction + "|" + input + "|" + output);
        }
    }

    /**
     * Chat 格式样本。
     *
     * @param messages 多轮对话消息列表
     */
    public record ChatSample(
            List<ChatMessage> messages
    ) implements DatasetSample {

        public ChatSample {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("messages 不能为空");
            }
            messages = List.copyOf(messages);
        }

        @Override
        public String toJsonLine() {
            List<Map<String, String>> msgs = messages.stream()
                    .map(m -> {
                        Map<String, String> map = new LinkedHashMap<>();
                        map.put("role", m.role());
                        map.put("content", m.content());
                        return map;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messages", msgs);
            return writeJson(map);
        }

        @Override
        public int textLength() {
            return messages.stream()
                    .mapToInt(m -> m.content().length())
                    .sum();
        }

        @Override
        public String contentHash() {
            String joined = messages.stream()
                    .map(m -> m.role() + ":" + m.content())
                    .collect(Collectors.joining("||"));
            return sha1(joined);
        }
    }

    /**
     * Chat 对话消息。
     *
     * @param role    角色（system / user / assistant）
     * @param content 消息内容
     */
    public record ChatMessage(String role, String content) {

        public ChatMessage {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("role 不能为空");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content 不能为空");
            }
        }

        /** 快捷构造：user 角色消息 */
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        /** 快捷构造：assistant 角色消息 */
        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }

        /** 快捷构造：system 角色消息 */
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }
    }

    /**
     * Preference 格式样本（用于 DPO 偏好优化）。
     *
     * @param prompt   提示词
     * @param chosen   偏好回答（高质量）
     * @param rejected 拒绝回答（低质量）
     */
    public record PreferenceSample(
            String prompt,
            String chosen,
            String rejected
    ) implements DatasetSample {

        public PreferenceSample {
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("prompt 不能为空");
            }
            if (chosen == null || chosen.isBlank()) {
                throw new IllegalArgumentException("chosen 不能为空");
            }
            if (rejected == null || rejected.isBlank()) {
                throw new IllegalArgumentException("rejected 不能为空");
            }
        }

        @Override
        public String toJsonLine() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("prompt", prompt);
            map.put("chosen", chosen);
            map.put("rejected", rejected);
            return writeJson(map);
        }

        @Override
        public int textLength() {
            return prompt.length() + chosen.length() + rejected.length();
        }

        @Override
        public String contentHash() {
            return sha1(prompt + "|" + chosen + "|" + rejected);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 验证结果记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 数据集验证结果。
     *
     * @param valid         是否通过验证
     * @param errors        错误信息列表
     * @param warnings      警告信息列表
     * @param checkedCount  检查的样本数
     */
    public record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            int checkedCount
    ) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 创建一个通过的验证结果 */
        public static ValidationResult ok(int count, List<String> warnings) {
            return new ValidationResult(true, List.of(), warnings, count);
        }

        /** 创建一个失败的验证结果 */
        public static ValidationResult failed(List<String> errors, int count) {
            return new ValidationResult(false, errors, List.of(), count);
        }
    }

    /**
     * 数据集长度统计。
     *
     * @param sampleCount  样本总数
     * @param minLength    最短样本字符数
     * @param maxLength    最长样本字符数
     * @param avgLength    平均样本字符数
     * @param totalLength  总字符数
     * @param estimatedTokens 估算总 token 数（约 3 字符/token）
     */
    public record LengthStatistics(
            int sampleCount,
            int minLength,
            int maxLength,
            double avgLength,
            int totalLength,
            int estimatedTokens
    ) {
    }

    /**
     * 去重检查结果。
     *
     * @param totalSamples    样本总数
     * @param uniqueSamples   去重后样本数
     * @param duplicateCount  重复样本数
     * @param duplicateHashes 重复样本的哈希值列表
     */
    public record DeduplicationResult(
            int totalSamples,
            int uniqueSamples,
            int duplicateCount,
            List<String> duplicateHashes
    ) {
        public DeduplicationResult {
            duplicateHashes = duplicateHashes == null ? List.of() : List.copyOf(duplicateHashes);
        }

        /** 重复率（0.0 - 1.0） */
        public double duplicateRate() {
            return totalSamples == 0 ? 0.0 : (double) duplicateCount / totalSamples;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 静态工厂方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建数据集的便捷工厂方法。
     *
     * @param name     数据集名称
     * @param format   数据格式
     * @param samples  样本列表
     * @return 数据集实例
     */
    public static FineTuneDataset of(String name, Format format, List<DatasetSample> samples) {
        return new FineTuneDataset(name, "", format, samples, Map.of(), LocalDateTime.now());
    }

    /**
     * 创建带描述和元数据的数据集。
     *
     * @param name        数据集名称
     * @param description 数据集描述
     * @param format      数据格式
     * @param samples     样本列表
     * @param metadata    元数据
     * @return 数据集实例
     */
    public static FineTuneDataset of(String name, String description, Format format,
                                     List<DatasetSample> samples, Map<String, Object> metadata) {
        return new FineTuneDataset(name, description, format, samples, metadata, LocalDateTime.now());
    }

    // ════════════════════════════════════════════════════════════════
    // 验证方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 验证数据集格式与内容完整性。
     *
     * <p>检查项：
     * <ol>
     *   <li>样本列表非空</li>
     *   <li>每条样本的类型与声明的 {@link #format} 一致</li>
     *   <li>必填字段非空（由各 record 构造器保证，此处做运行时复核）</li>
     * </ol>
     *
     * @return 验证结果
     */
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (samples.isEmpty()) {
            errors.add("数据集样本列表为空");
            return ValidationResult.failed(errors, 0);
        }

        int checked = 0;
        for (int i = 0; i < samples.size(); i++) {
            DatasetSample sample = samples.get(i);
            checked++;

            // 检查样本类型与声明的格式是否匹配
            boolean typeMatch = switch (format) {
                case INSTRUCTION -> sample instanceof InstructionSample;
                case CHAT -> sample instanceof ChatSample;
                case PREFERENCE -> sample instanceof PreferenceSample;
            };
            if (!typeMatch) {
                errors.add(String.format("第 %d 条样本类型与声明格式 %s 不匹配（实际类型: %s）",
                        i + 1, format, sample.getClass().getSimpleName()));
                continue;
            }

            // Chat 格式额外检查：消息角色合法性
            if (sample instanceof ChatSample chat) {
                for (int j = 0; j < chat.messages().size(); j++) {
                    ChatMessage msg = chat.messages().get(j);
                    if (!isValidRole(msg.role())) {
                        errors.add(String.format("第 %d 条样本的第 %d 条消息角色非法: %s",
                                i + 1, j + 1, msg.role()));
                    }
                }
                // 检查是否以 assistant 结尾（微调目标）
                if (!chat.messages().isEmpty()) {
                    ChatMessage last = chat.messages().get(chat.messages().size() - 1);
                    if (!"assistant".equals(last.role())) {
                        warnings.add(String.format("第 %d 条样本未以 assistant 消息结尾，可能影响微调效果", i + 1));
                    }
                }
            }

            // Preference 格式额外检查：chosen 与 rejected 不应完全相同
            if (sample instanceof PreferenceSample pref) {
                if (pref.chosen().equals(pref.rejected())) {
                    errors.add(String.format("第 %d 条样本的 chosen 与 rejected 完全相同", i + 1));
                }
            }
        }

        if (!errors.isEmpty()) {
            return ValidationResult.failed(errors, checked);
        }
        return ValidationResult.ok(checked, warnings);
    }

    /**
     * 统计数据集长度信息。
     *
     * @return 长度统计结果
     */
    public LengthStatistics lengthStatistics() {
        if (samples.isEmpty()) {
            return new LengthStatistics(0, 0, 0, 0.0, 0, 0);
        }

        int min = Integer.MAX_VALUE;
        int max = 0;
        int total = 0;
        for (DatasetSample sample : samples) {
            int len = sample.textLength();
            min = Math.min(min, len);
            max = Math.max(max, len);
            total += len;
        }
        double avg = (double) total / samples.size();
        int estimatedTokens = (int) Math.ceil(total / 3.0); // 约 3 字符/token

        return new LengthStatistics(samples.size(), min, max, avg, total, estimatedTokens);
    }

    /**
     * 检查数据集中的重复样本。
     *
     * <p>基于每条样本的 {@link DatasetSample#contentHash()} 进行去重检测。
     *
     * @return 去重检查结果
     */
    public DeduplicationResult checkDuplicates() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (DatasetSample sample : samples) {
            String hash = sample.contentHash();
            if (!seen.add(hash)) {
                duplicates.add(hash);
            }
        }

        return new DeduplicationResult(
                samples.size(),
                seen.size(),
                duplicates.size(),
                duplicates
        );
    }

    /**
     * 返回去重后的新数据集。
     *
     * @return 去重后的数据集（保留首次出现的样本）
     */
    public FineTuneDataset deduplicated() {
        Set<String> seen = new HashSet<>();
        List<DatasetSample> unique = new ArrayList<>();
        for (DatasetSample sample : samples) {
            if (seen.add(sample.contentHash())) {
                unique.add(sample);
            }
        }
        return new FineTuneDataset(name, description, format, unique, metadata, createdAt);
    }

    // ════════════════════════════════════════════════════════════════
    // 序列化方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 将数据集序列化为 JSONL 格式字符串（每行一个 JSON 对象）。
     *
     * @return JSONL 字符串
     */
    public String toJsonl() {
        StringBuilder sb = new StringBuilder();
        for (DatasetSample sample : samples) {
            sb.append(sample.toJsonLine()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 将数据集元信息序列化为 JSON（不含样本明细）。
     *
     * @return JSON 字符串
     */
    public String toSummaryJson() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", name);
        summary.put("description", description);
        summary.put("format", format.name());
        summary.put("sampleCount", samples.size());
        summary.put("createdAt", createdAt.toString());
        summary.put("metadata", metadata);
        return writeJson(summary);
    }

    // ════════════════════════════════════════════════════════════════
    // 从 Agent 输出自动生成训练数据
    // ════════════════════════════════════════════════════════════════

    /**
     * 从项目已有的 Agent 输出自动生成 Instruction 格式训练数据。
     *
     * <p>将 Agent 的输入（用户请求/上下文）作为 instruction + input，
     * 将 Agent 的输出作为 output，构建指令微调样本。
     *
     * @param name       数据集名称
     * @param agentOutputs Agent 输出列表，每个元素包含 input（输入上下文）和 output（Agent 产出）
     * @return Instruction 格式数据集
     */
    public static FineTuneDataset fromAgentOutputs(String name, List<AgentOutputEntry> agentOutputs) {
        List<DatasetSample> samples = new ArrayList<>();
        for (AgentOutputEntry entry : agentOutputs) {
            samples.add(new InstructionSample(
                    entry.instruction() != null ? entry.instruction() : "根据输入上下文生成内容",
                    entry.input() != null ? entry.input() : "",
                    entry.output()
            ));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "agent-outputs");
        metadata.put("autoGenerated", true);
        metadata.put("entryCount", agentOutputs.size());

        log.info("[FineTuneDataset] 从 Agent 输出自动生成数据集: name={}, samples={}, format=INSTRUCTION",
                name, samples.size());
        return new FineTuneDataset(name, "由 Agent 输出自动生成的指令微调数据集",
                Format.INSTRUCTION, samples, metadata, LocalDateTime.now());
    }

    /**
     * 从 Agent 对话记录自动生成 Chat 格式训练数据。
     *
     * @param name           数据集名称
     * @param conversationLogs 对话记录列表（每条包含多轮消息）
     * @return Chat 格式数据集
     */
    public static FineTuneDataset fromConversations(String name, List<List<ChatMessage>> conversationLogs) {
        List<DatasetSample> samples = new ArrayList<>();
        for (List<ChatMessage> messages : conversationLogs) {
            samples.add(new ChatSample(messages));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "conversation-logs");
        metadata.put("autoGenerated", true);

        log.info("[FineTuneDataset] 从对话记录自动生成数据集: name={}, samples={}, format=CHAT",
                name, samples.size());
        return new FineTuneDataset(name, "由 Agent 对话记录自动生成的对话微调数据集",
                Format.CHAT, samples, metadata, LocalDateTime.now());
    }

    /**
     * Agent 输出条目（用于自动生成训练数据）。
     *
     * @param instruction 指令描述（可选，默认"根据输入上下文生成内容"）
     * @param input       输入上下文（Agent 接收的用户请求/上下文）
     * @param output      Agent 产出内容
     */
    public record AgentOutputEntry(String instruction, String input, String output) {

        public AgentOutputEntry {
            if (output == null || output.isBlank()) {
                throw new IllegalArgumentException("Agent 输出不能为空");
            }
            input = input == null ? "" : input;
        }

        /** 快捷构造：仅有输入和输出 */
        public static AgentOutputEntry of(String input, String output) {
            return new AgentOutputEntry(null, input, output);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 内部工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查角色名称是否合法。
     */
    private static boolean isValidRole(String role) {
        return "system".equals(role) || "user".equals(role) || "assistant".equals(role);
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    private static String writeJson(Map<String, Object> map) {
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }

    /**
     * 简易 SHA-1 哈希（用于去重，非安全用途）。
     */
    private static String sha1(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 降级：使用 hashCode
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 创建并配置 ObjectMapper 实例。
     */
    private static ObjectMapper createObjectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    // ════════════════════════════════════════════════════════════════
    // 便捷查询方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取样本数量。
     *
     * @return 样本数
     */
    public int sampleCount() {
        return samples.size();
    }

    /**
     * 判断数据集是否为空。
     *
     * @return true 表示无样本
     */
    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /**
     * 返回数据集格式名称（小写）。
     *
     * @return 格式名称，如 "instruction"、"chat"、"preference"
     */
    public String formatName() {
        return format.name().toLowerCase(Locale.ROOT);
    }
}
