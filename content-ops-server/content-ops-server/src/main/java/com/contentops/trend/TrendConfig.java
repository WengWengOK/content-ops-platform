package com.contentops.trend;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 热点监控模块配置：启用定时轮询。
 */
@Configuration
@EnableScheduling
public class TrendConfig {
}
