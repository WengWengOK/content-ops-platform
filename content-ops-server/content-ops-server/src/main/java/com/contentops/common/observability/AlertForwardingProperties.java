package com.contentops.common.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 告警转发配置（contentops.observability.alerts.*）。
 *
 * <p>Webhook 机器人在飞书/企业微信群内创建「自定义机器人」获得：
 * <ul>
 *   <li>飞书：https://open.feishu.cn/open-apis/bot/v2/hook/{token}</li>
 *   <li>企业微信：https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={key}</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.observability.alerts")
public class AlertForwardingProperties {

    /** 飞书机器人 Webhook（为空则跳过飞书） */
    private String feishuWebhook = "";

    /** 企业微信机器人 Webhook（为空则跳过企微） */
    private String wecomWebhook = "";
}
