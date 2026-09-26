package com.contentops.comment;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 评论模块配置：开启定时采集能力（与热点轮询共用 Spring 调度器）。
 */
@Configuration
@EnableScheduling
public class CommentConfig {
}