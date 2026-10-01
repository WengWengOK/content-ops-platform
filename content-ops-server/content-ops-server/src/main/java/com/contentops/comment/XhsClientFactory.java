package com.contentops.comment;

import com.contentops.common.credential.PlatformCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 按「平台账号凭据」构建独立的评论接口客户端。
 *
 * <p>做法是把全局 {@link CommentProperties} 深拷贝一份并覆盖凭据里的
 * preset / base-url / access-token，再构造一个独立的 {@link XhsCommentApiClient}。
 * 这样多账号之间互不影响，也不会污染全局配置（并发安全：每次调用都是新副本）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XhsClientFactory {

    private final CommentProperties properties;
    private final ObjectMapper objectMapper;

    /** 基于凭据构建客户端；credential 为空时返回全局配置客户端。 */
    public XhsCommentApiClient forCredential(PlatformCredential credential) {
        if (credential == null) {
            return new XhsCommentApiClient(properties, objectMapper);
        }
        CommentProperties copy = objectMapper.convertValue(properties, CommentProperties.class);
        CommentProperties.XiaohongshuProperties xhs = copy.getXiaohongshu();
        xhs.setEnabled(true);
        if (credential.getPreset() != null && !credential.getPreset().isBlank()) {
            xhs.setPreset(credential.getPreset().trim());
        }
        if (credential.getBaseUrl() != null && !credential.getBaseUrl().isBlank()) {
            xhs.setBaseUrl(credential.getBaseUrl().trim());
        }
        if (credential.getAccessToken() != null && !credential.getAccessToken().isBlank()) {
            xhs.setAccessToken(credential.getAccessToken());
        }
        log.debug("[Credential] 构建凭据客户端: id={}, account={}, baseUrl={}",
                credential.getCredentialId(), credential.getAccountName(), xhs.getBaseUrl());
        return new XhsCommentApiClient(copy, objectMapper);
    }
}