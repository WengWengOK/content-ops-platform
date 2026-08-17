package com.contentops.trend;

import java.util.List;

/**
 * 热点数据源 SPI：多平台热榜聚合（参考 TrendRadar 的数据源抽象）。
 *
 * <p>实现：
 * <ul>
 *   <li>{@link MockTrendProvider} — 开发/演示用内置热榜（默认）</li>
 *   <li>{@link NewsNowTrendProvider} — 对接 newsnow 聚合 API（可配置，含域名安全校验）</li>
 * </ul>
 */
public interface TrendProvider {

    /** 数据源名称，用于日志/配置识别 */
    String name();

    /** 支持的热榜平台 code 列表 */
    List<String> supportedPlatforms();

    /**
     * 拉取指定平台当前热榜。
     *
     * @param platform 平台 code；null 表示拉取全部
     * @param limit    每个平台最多返回条数
     * @return 热点条目列表（含 capturedAt）
     */
    List<TrendHotspot> fetchHotspots(String platform, int limit);
}
