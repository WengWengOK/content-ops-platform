package com.contentops.common.credential;

/**
 * 凭据解密失败（通常是轮换密钥后缺少旧密钥）。
 *
 * <p>与旧实现的关键区别：解密失败必须显式失败，<b>绝不把密文当令牌返回</b>，
 * 否则会把「解密失败」伪装成「令牌错误」，排查成本极高。
 */
public class CredentialDecryptException extends RuntimeException {

    public CredentialDecryptException(String message) {
        super(message);
    }

    public CredentialDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}