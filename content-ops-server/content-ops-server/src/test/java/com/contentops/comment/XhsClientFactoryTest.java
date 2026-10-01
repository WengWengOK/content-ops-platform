package com.contentops.comment;

import com.contentops.common.credential.PlatformCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭据客户端工厂测试：按凭据覆盖 base-url/token/preset，且不污染全局配置。
 */
class XhsClientFactoryTest {

    @Test
    @DisplayName("按凭据构建客户端：覆盖地址/令牌/预设，全局配置保持不变")
    void forCredentialOverridesWithoutMutatingGlobal() {
        CommentProperties global = new CommentProperties();
        global.getXiaohongshu().setEnabled(false);
        global.getXiaohongshu().setBaseUrl("http://global");
        global.getXiaohongshu().setAccessToken("global-token");
        XhsClientFactory factory = new XhsClientFactory(global, new ObjectMapper());

        XhsCommentApiClient client = factory.forCredential(PlatformCredential.builder()
                .credentialId("c1").ownerId("owner-1").platform("xiaohongshu")
                .preset("xhs-mcp").baseUrl("http://127.0.0.1:18061").accessToken("token-b").build());

        assertThat(client.config().isEnabled()).isTrue();
        assertThat(client.config().getBaseUrl()).isEqualTo("http://127.0.0.1:18061");
        assertThat(client.config().getAccessToken()).isEqualTo("token-b");
        assertThat(client.isConfigured()).isTrue();

        assertThat(global.getXiaohongshu().isEnabled()).isFalse();
        assertThat(global.getXiaohongshu().getBaseUrl()).isEqualTo("http://global");
        assertThat(global.getXiaohongshu().getAccessToken()).isEqualTo("global-token");
    }
}