package com.contentops.comment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评论区 AI 助手配置（{@code contentops.comment.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.comment")
public class CommentProperties {

    /** 是否启用评论区 AI 助手 */
    private boolean enabled = true;

    /** 评论采集轮询间隔（毫秒），MVP 阶段仅手动/定时采集 */
    private long collectMs = 60_000;

    /** 单次模拟采集条数 */
    private int mockCount = 10;
}
