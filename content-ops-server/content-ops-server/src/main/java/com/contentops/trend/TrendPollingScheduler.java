package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 热点轮询：按配置间隔拉取多平台热榜快照入库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendPollingScheduler {

    private final TrendService trendService;
    private final TrendProperties properties;

    @Scheduled(fixedDelayString = "${contentops.trend.poll-ms:1800000}")
    public void pollTrends() {
        if (!properties.isEnabled()) {
            return;
        }
        log.debug("[Trend] 定时轮询热点开始");
        trendService.refreshHotspots();
    }
}
