package com.contentops.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DashScope（百炼）配置绑定。
 *
 * <p>通过 {@code dashscope.*} 在 application.yml 中绑定。
 * 当前仅作为配置存放点（{@code enabled=false}），后期按需接入：
 * <ul>
 *   <li>在 {@link AiModelConfig} 中根据 {@code enabled} 选择构建
 *       {@code OpenAiChatModel} 时指向 DashScope 兼容模式地址；</li>
 *   <li>或将 {@code contentops.model-routing.base-url / api-key / default-model}
 *       直接覆盖为本配置对应的取值即可零代码切换。</li>
 * </ul>
 *
 * <h3>常用模型速查</h3>
 * <ul>
 *   <li>qwen-max：最强推理与创作（成本最高）</li>
 *   <li>qwen-plus：创意类首选，效果与成本均衡</li>
 *   <li>qwen-turbo：格式化类首选，速度快、价格低、确定性高</li>
 *   <li>qwen3-8b / qwen3-72b：通义千问 3 系列</li>
 *   <li>qwen-vl-plus：多模态视觉理解</li>
 *   <li>text-embedding-v3：文本向量嵌入（512/1024/1536 维可选）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dashscope")
public class DashScopeProperties {

    /** 是否启用 DashScope（默认关闭，后期改为 true 后接入 AiModelConfig） */
    private boolean enabled = false;

    /** DashScope API Key（支持通过 DASHSCOPE_API_KEY 环境变量覆盖） */
    private String apiKey = "sk-placeholder";

    /**
     * DashScope 兼容模式 Base URL。
     * LangChain4j 的 OpenAiChatModel 通过兼容协议调用 DashScope，
     * 必须使用 {@code /compatible-mode/v1} 路径。
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 按用途分类的默认模型名（可按需扩展阶段级覆盖） */
    private Models models = new Models();

    /** 默认采样温度（AiModelConfig 可按此覆盖 model-routing 同名字段） */
    private double defaultTemperature = 0.8;

    /** 默认最大输出 token 数 */
    private int defaultMaxTokens = 4096;

    @Data
    public static class Models {

        /** 创意类模型（选题/内容/配图）—— 默认 qwen-plus */
        private String creative = "qwen-plus";

        /** 格式化类模型（发布/分析/优化）—— 默认 qwen-turbo */
        private String formatting = "qwen-turbo";

        /** 视觉分析模型（图片理解）—— 默认 qwen-vl-plus */
        private String vision = "qwen-vl-plus";

        /** 文本嵌入模型（知识库/风格画像/向量检索）—— 默认 text-embedding-v3 */
        private String embedding = "text-embedding-v3";
    }
}
