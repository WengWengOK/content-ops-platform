package com.contentops.common.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for all content platform APIs.
 *
 * <p>Bindable via application.yml under {@code contentops.platform}:
 * <pre>
 * contentops:
 *   platform:
 *     image-generation:
 *       api-key: ${OPENAI_API_KEY:}
 *       base-url: ${OPENAI_BASE_URL:https://api.openai.com}
 *       model: dall-e-3
 *       size: 1024x1024
 *       quality: standard
 *       style: vivid
 *     wechat:
 *       app-id: ${WECHAT_APP_ID:}
 *       app-secret: ${WECHAT_APP_SECRET:}
 *       enabled: false
 *     douyin:
 *       client-key: ${DOUYIN_CLIENT_KEY:}
 *       client-secret: ${DOUYIN_CLIENT_SECRET:}
 *       enabled: false
 *     xiaohongshu:
 *       app-id: ${XHS_APP_ID:}
 *       app-secret: ${XHS_APP_SECRET:}
 *       enabled: false
 *     bilibili:
 *       app-id: ${BILI_APP_ID:}
 *       app-secret: ${BILI_APP_SECRET:}
 *       enabled: false
 *     kuaishou:
 *       app-id: ${KS_APP_ID:}
 *       app-secret: ${KS_APP_SECRET:}
 *       enabled: false
 * </pre>
 *
 * <p>Each platform is independently toggleable via its {@code enabled} flag.
 * When disabled, the corresponding service returns graceful fallback messages.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.platform")
public class PlatformApiProperties {

    private ImageGenerationConfig imageGeneration = new ImageGenerationConfig();
    private WechatConfig wechat = new WechatConfig();
    private DouyinConfig douyin = new DouyinConfig();
    private XiaohongshuConfig xiaohongshu = new XiaohongshuConfig();
    private BilibiliConfig bilibili = new BilibiliConfig();
    private KuaishouConfig kuaishou = new KuaishouConfig();

    @Data
    public static class ImageGenerationConfig {
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com";
        /** 生图端点路径：ARK 为 /images/generations；OpenAI 为 /v1/images/generations */
        private String path = "/v1/images/generations";
        private String model = "dall-e-3";
        private String size = "1024x1024";
        private String quality = "standard";
        private String style = "vivid";
        private int timeoutSeconds = 60;
    }

    @Data
    public static class WechatConfig {
        private String appId = "";
        private String appSecret = "";
        private boolean enabled = false;
        private String baseUrl = "https://api.weixin.qq.com";
    }

    @Data
    public static class DouyinConfig {
        private String clientKey = "";
        private String clientSecret = "";
        private boolean enabled = false;
        private String baseUrl = "https://open.douyin.com";
    }

    @Data
    public static class XiaohongshuConfig {
        private String appId = "";
        private String appSecret = "";
        private boolean enabled = false;
        private String baseUrl = "https://open.xiaohongshu.com";
    }

    @Data
    public static class BilibiliConfig {
        private String appId = "";
        private String appSecret = "";
        private boolean enabled = false;
        private String baseUrl = "https://open.bilibili.com";
    }

    @Data
    public static class KuaishouConfig {
        private String appId = "";
        private String appSecret = "";
        private boolean enabled = false;
        private String baseUrl = "https://open.kuaishou.com";
    }
}
