package com.contentops.common.multimodal;

import com.contentops.common.metrics.TokenEstimator;
import com.contentops.common.metrics.TokenMetricsService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 音频处理服务。
 *
 * <p>集成外部 API 实现「语音转文字（STT）」「文字转语音（TTS）」「音频内容摘要」三大能力，
 * 支持多种音频格式，并提供完善的降级与指标记录机制。
 *
 * <h3>支持的能力</h3>
 * <ul>
 *   <li><b>语音转文字 STT</b>（{@link #speechToText}）：调用 Whisper API 将音频转录为文本，
 *       支持 MP3、WAV、M4A 格式</li>
 *   <li><b>文字转语音 TTS</b>（{@link #textToSpeech}）：将文本合成为语音，返回音频字节流</li>
 *   <li><b>音频内容摘要</b>（{@link #summarizeAudio}）：先 STT 转录，再调用 LLM 生成摘要</li>
 * </ul>
 *
 * <h3>支持的音频格式</h3>
 * <p>{@code MP3}、{@code WAV}、{@code M4A}（对应 OpenAI Whisper 支持的格式）。
 * 其他格式会在调用前被校验并返回降级提示。
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>API Key 未配置时，STT/TTS 返回降级提示文本而非抛出异常</li>
 *   <li>API 调用失败时，捕获异常并返回错误描述，同时通过 {@link TokenMetricsService} 记录失败指标</li>
 *   <li>摘要能力依赖 {@link ChatModel}，模型不可用时降级为返回转录文本本身</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 语音转文字
 * String transcript = audioProcessingService.speechToText("/data/audio/meeting.mp3", "zh");
 *
 * // 文字转语音
 * byte[] audio = audioProcessingService.textToSpeech("你好，世界", "alloy", "mp3");
 *
 * // 音频摘要
 * String summary = audioProcessingService.summarizeAudio("/data/audio/meeting.mp3", "请生成会议纪要");
 * }</pre>
 *
 * @see TokenMetricsService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioProcessingService {

    private final ChatModel chatModel;
    private final TokenMetricsService tokenMetricsService;
    private final AudioProperties properties;

    /** 支持的音频格式（小写扩展名） */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("mp3", "wav", "m4a");

    /** 音频处理的 Agent 阶段标签，用于指标记录 */
    private static final String AUDIO_STAGE = "audio-processing";

    /** 摘要系统提示词 */
    private static final String SYSTEM_SUMMARIZE = """
            你是一个专业的音频内容摘要助手。请根据转录文本生成结构化摘要。
            要求：
            1. 提取核心要点（不超过 5 条）
            2. 标注关键决策与待办事项
            3. 保留重要的人名、数字、时间
            4. 语言简洁，逻辑清晰""";

    /**
     * 语音转文字（STT）。
     *
     * <p>读取本地音频文件，调用 Whisper API 转录为文本。
     *
     * @param filePath 音频文件路径（支持 MP3/WAV/M4A）
     * @param language 语言代码（如 "zh"、"en"），为空时由模型自动检测
     * @return 转录文本；不可用时返回降级提示
     */
    public String speechToText(String filePath, String language) {
        if (!isSttAvailable()) {
            return degrade("语音转文字", filePath);
        }
        Optional<Path> validFile = validateAudioFile(filePath);
        if (validFile.isEmpty()) {
            return "[语音转文字失败] 音频文件无效或格式不支持: " + filePath;
        }

        Instant start = Instant.now();
        try {
            byte[] audioBytes = Files.readAllBytes(validFile.get());
            String transcript = callWhisperApi(audioBytes, fileName(validFile.get()), language);
            recordAudioMetrics(filePath, transcript, true, Duration.between(start, Instant.now()), "stt");
            return transcript;
        } catch (Exception e) {
            log.error("[Audio] 语音转文字失败: {}", filePath, e);
            String errorDesc = "[语音转文字失败] " + e.getMessage();
            recordAudioMetrics(filePath, errorDesc, false, Duration.between(start, Instant.now()), "stt");
            return errorDesc;
        }
    }

    /**
     * 语音转文字（使用自动语言检测）。
     *
     * @param filePath 音频文件路径
     * @return 转录文本
     */
    public String speechToText(String filePath) {
        return speechToText(filePath, null);
    }

    /**
     * 文字转语音（TTS）。
     *
     * @param text     要合成的文本
     * @param voice    音色（如 alloy、echo、fable、onyx、nova、shimmer），为空时使用默认
     * @param format   输出格式（mp3、opus、aac、flac），为空时使用默认
     * @return 合成的音频字节流；不可用时返回空数组
     */
    public byte[] textToSpeech(String text, String voice, String format) {
        if (!isTtsAvailable()) {
            log.warn("[Audio] 文字转语音不可用, API Key 未配置");
            return new byte[0];
        }
        if (text == null || text.isBlank()) {
            log.warn("[Audio] 文字转语音输入文本为空");
            return new byte[0];
        }

        Instant start = Instant.now();
        try {
            byte[] audio = callTtsApi(text, voice, format);
            recordAudioMetrics(text, "[TTS音频 " + audio.length + " bytes]", true,
                    Duration.between(start, Instant.now()), "tts");
            return audio;
        } catch (Exception e) {
            log.error("[Audio] 文字转语音失败", e);
            recordAudioMetrics(text, "[TTS失败 " + e.getMessage() + "]", false,
                    Duration.between(start, Instant.now()), "tts");
            return new byte[0];
        }
    }

    /**
     * 文字转语音（使用默认音色与格式）。
     *
     * @param text 要合成的文本
     * @return 合成的音频字节流
     */
    public byte[] textToSpeech(String text) {
        return textToSpeech(text, null, null);
    }

    /**
     * 音频内容摘要。
     *
     * <p>流程：STT 转录 → LLM 摘要。转录失败时直接返回降级提示，
     * 模型不可用时降级返回转录文本本身。
     *
     * @param filePath 音频文件路径
     * @param hint     摘要提示（如"请生成会议纪要"），为空时使用默认摘要提示
     * @return 摘要文本
     */
    public String summarizeAudio(String filePath, String hint) {
        // 1. 先转录
        String transcript = speechToText(filePath, null);
        if (transcript == null || transcript.startsWith("[") || transcript.isBlank()) {
            return transcript;
        }

        // 2. 模型不可用时降级返回转录文本
        if (chatModel == null) {
            log.warn("[Audio] ChatModel 不可用, 摘要降级为返回转录文本");
            return transcript;
        }

        // 3. 调用 LLM 摘要
        Instant start = Instant.now();
        boolean success = false;
        String summary = transcript;
        try {
            String prompt = (hint == null || hint.isBlank())
                    ? "请对以下音频转录文本生成结构化摘要。"
                    : hint;
            String userContent = prompt + "\n\n--- 转录文本 ---\n" + transcript;
            var chatResponse = chatModel.chat(SystemMessage.from(SYSTEM_SUMMARIZE),
                    UserMessage.from(userContent));
            AiMessage aiMessage = chatResponse.aiMessage();
            summary = aiMessage == null ? transcript : aiMessage.text();
            if (summary == null || summary.isBlank()) {
                summary = transcript;
            }
            success = true;
            return summary;
        } catch (Exception e) {
            log.error("[Audio] 音频摘要生成失败: {}", filePath, e);
            summary = "[音频摘要失败] " + e.getMessage() + "\n\n--- 转录文本 ---\n" + transcript;
            return summary;
        } finally {
            recordAudioMetrics(transcript, summary, success,
                    Duration.between(start, Instant.now()), "summarize");
        }
    }

    /**
     * 音频内容摘要（使用默认摘要提示）。
     *
     * @param filePath 音频文件路径
     * @return 摘要文本
     */
    public String summarizeAudio(String filePath) {
        return summarizeAudio(filePath, null);
    }

    // ════════════════════════════════════════════════════════════════
    //  外部 API 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 调用 Whisper 语音转文字 API。
     *
     * @param audioBytes 音频字节数据
     * @param fileName   文件名（含扩展名，用于推断 MIME 类型）
     * @param language   语言代码（可空）
     * @return 转录文本
     */
    private String callWhisperApi(byte[] audioBytes, String fileName, String language) {
        RestClient client = RestClient.builder()
                .baseUrl(properties.getStt().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getStt().getApiKey())
                .build();

        ByteArrayResource resource = new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", resource);
        parts.add("model", properties.getStt().getModel());
        if (language != null && !language.isBlank()) {
            parts.add("language", language);
        }
        if (properties.getStt().getResponseFormat() != null
                && !properties.getStt().getResponseFormat().isBlank()) {
            parts.add("response_format", properties.getStt().getResponseFormat());
        }

        WhisperResponse response = client.post()
                .uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(WhisperResponse.class);

        if (response != null && response.getText() != null) {
            return response.getText();
        }
        return "[语音转文字失败] API 未返回文本";
    }

    /**
     * 调用文字转语音 API。
     *
     * @param text   要合成的文本
     * @param voice  音色
     * @param format 输出格式
     * @return 音频字节流
     */
    private byte[] callTtsApi(String text, String voice, String format) {
        RestClient client = RestClient.builder()
                .baseUrl(properties.getTts().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getTts().getApiKey())
                .build();

        TtsRequest request = new TtsRequest(
                properties.getTts().getModel(),
                text,
                voice != null && !voice.isBlank() ? voice : properties.getTts().getVoice(),
                format != null && !format.isBlank() ? format : properties.getTts().getFormat());

        return client.post()
                .uri("/v1/audio/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(byte[].class);
    }

    // ════════════════════════════════════════════════════════════════
    //  校验与降级
    // ════════════════════════════════════════════════════════════════

    /**
     * 判断 STT（语音转文字）是否可用。
     *
     * @return true 表示 API Key 已配置
     */
    public boolean isSttAvailable() {
        String key = properties.getStt().getApiKey();
        return key != null && !key.isBlank() && !"sk-placeholder".equals(key);
    }

    /**
     * 判断 TTS（文字转语音）是否可用。
     *
     * @return true 表示 API Key 已配置
     */
    public boolean isTtsAvailable() {
        String key = properties.getTts().getApiKey();
        return key != null && !key.isBlank() && !"sk-placeholder".equals(key);
    }

    /**
     * 校验音频文件是否存在且格式受支持。
     *
     * @param filePath 文件路径
     * @return 有效时返回 Path，无效时返回 empty
     */
    private Optional<Path> validateAudioFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            log.warn("[Audio] 音频文件不存在或不是常规文件: {}", filePath);
            return Optional.empty();
        }
        String ext = extension(filePath);
        if (!SUPPORTED_FORMATS.contains(ext)) {
            log.warn("[Audio] 不支持的音频格式: {} (支持: {})", ext, SUPPORTED_FORMATS);
            return Optional.empty();
        }
        return Optional.of(path);
    }

    /**
     * 提取文件扩展名（小写，不含点）。
     */
    private String extension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(dot + 1).toLowerCase();
    }

    /**
     * 从 Path 提取文件名。
     */
    private String fileName(Path path) {
        return path.getFileName().toString();
    }

    /**
     * 多模态不可用时的降级返回。
     *
     * @param capability 能力名称
     * @param audioRef   音频引用
     * @return 降级提示文本
     */
    private String degrade(String capability, String audioRef) {
        log.warn("[Audio] {} 不可用, API Key 未配置, audio={}", capability, audioRef);
        return "[" + capability + "不可用] 音频处理 API Key 未配置。"
                + "请在 application.yml 中设置 contentops.audio.stt.api-key / tts.api-key。";
    }

    /**
     * 记录音频处理指标。
     */
    private void recordAudioMetrics(String input, String output, boolean success,
                                     Duration duration, String operation) {
        try {
            int inputTokens = TokenEstimator.estimate(input);
            int outputTokens = TokenEstimator.estimate(output);
            String stageTag = AUDIO_STAGE + ":" + operation;
            tokenMetricsService.recordTokenUsage("audio", stageTag, inputTokens, outputTokens);
            tokenMetricsService.recordAgentCall(stageTag, success);
            tokenMetricsService.recordAgentDuration(stageTag, duration);
        } catch (Exception e) {
            log.warn("[Audio] 记录指标失败: {}", e.getMessage());
        }
    }

    /**
     * 读取音频文件字节数据的工具方法（供外部调用方使用）。
     *
     * @param filePath 音频文件路径
     * @return 字节数组
     * @throws IOException 读取失败时抛出
     */
    public byte[] readAudioBytes(String filePath) throws IOException {
        return Files.readAllBytes(Path.of(filePath));
    }

    // ════════════════════════════════════════════════════════════════
    //  API 请求 / 响应 DTO
    // ════════════════════════════════════════════════════════════════

    /**
     * Whisper API 响应 DTO。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WhisperResponse {
        /** 转录文本 */
        @JsonProperty("text")
        private String text;
    }

    /**
     * TTS API 请求 DTO。
     *
     * @param model   TTS 模型（如 tts-1、tts-1-hd）
     * @param input   要合成的文本
     * @param voice   音色
     * @param format  输出格式
     */
    public record TtsRequest(
            @JsonProperty("model") String model,
            @JsonProperty("input") String input,
            @JsonProperty("voice") String voice,
            @JsonProperty("response_format") String format
    ) {
    }

    // ════════════════════════════════════════════════════════════════
    //  结果 DTO 与配置
    // ════════════════════════════════════════════════════════════════

    /**
     * 音频处理结果 DTO（用于批量或结构化返回场景）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AudioResult {

        /** 音频文件路径或引用 */
        private String audioRef;

        /** 处理结果文本（转录文本 / 摘要）或状态描述 */
        private String content;

        /** 是否处理成功 */
        private boolean success;

        /** 处理耗时（毫秒） */
        private long elapsedMs;

        /** 失败时的错误信息 */
        private String errorMessage;
    }

    /**
     * 音频处理服务配置属性。
     *
     * <p>通过 {@code contentops.audio.*} 在 application.yml 中绑定。
     *
     * <h3>配置示例</h3>
     * <pre>{@code
     * contentops:
     *   audio:
     *     stt:
     *       api-key: ${OPENAI_API_KEY:}
     *       base-url: https://api.openai.com
     *       model: whisper-1
     *       response-format: json
     *     tts:
     *       api-key: ${OPENAI_API_KEY:}
     *       base-url: https://api.openai.com
     *       model: tts-1
     *       voice: alloy
     *       format: mp3
     * }</pre>
     */
    @Data
    @org.springframework.stereotype.Component
    @ConfigurationProperties(prefix = "contentops.audio")
    public static class AudioProperties {

        /** 语音转文字配置 */
        private SttConfig stt = new SttConfig();

        /** 文字转语音配置 */
        private TtsConfig tts = new TtsConfig();

        /**
         * 语音转文字（STT）配置。
         */
        @Data
        public static class SttConfig {
            /** OpenAI API Key */
            private String apiKey = "";
            /** API 基地址 */
            private String baseUrl = "https://api.openai.com";
            /** Whisper 模型名称 */
            private String model = "whisper-1";
            /** 响应格式（json / text / srt / verbose_json） */
            private String responseFormat = "json";
        }

        /**
         * 文字转语音（TTS）配置。
         */
        @Data
        public static class TtsConfig {
            /** OpenAI API Key */
            private String apiKey = "";
            /** API 基地址 */
            private String baseUrl = "https://api.openai.com";
            /** TTS 模型名称（tts-1 / tts-1-hd） */
            private String model = "tts-1";
            /** 默认音色（alloy / echo / fable / onyx / nova / shimmer） */
            private String voice = "alloy";
            /** 默认输出格式（mp3 / opus / aac / flac） */
            private String format = "mp3";
        }
    }

    /**
     * 获取支持的音频格式集合。
     *
     * @return 支持的格式（小写扩展名）
     */
    public Set<String> getSupportedFormats() {
        return SUPPORTED_FORMATS;
    }

    /**
     * 获取支持的格式列表（用于文档与校验）。
     *
     * @return 支持的格式列表
     */
    public List<String> listSupportedFormats() {
        return List.of("MP3", "WAV", "M4A");
    }
}
