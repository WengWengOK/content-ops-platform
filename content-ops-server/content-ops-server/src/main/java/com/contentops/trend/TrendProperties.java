package com.contentops.trend;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 热点监控模块配置（独立模块：数据源 + 轮询 + 查询）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.trend")
public class TrendProperties {

    /** 是否启用热点监控（轮询与查询；关闭时接口返回空但可用） */
    private boolean enabled = true;

    /** 数据源：mock（内置热榜）/ newsnow（聚合 API）/ sixty（60s 聚合 API，真实热榜，默认） */
    private String provider = "mock";

    /** 轮询间隔（毫秒），默认 30 分钟 */
    private long pollMs = 1_800_000;

    /** 查询默认返回条数 */
    private int defaultLimit = 20;

    /** newsnow 聚合 API 配置（provider=newsnow 时使用） */
    private Newsnow newsnow = new Newsnow();

    /** 60s 聚合 API 配置（provider=sixty 时使用，默认真实热榜数据源） */
    private Sixty sixty = new Sixty();

    /** AI 分析配置（相关性/真假识别/摘要，鱼皮式 AI 内容审核） */
    private Analysis analysis = new Analysis();

    /** 突发热点检测配置（跨快照热度/排名对比） */
    private Burst burst = new Burst();

    /** 实时通知配置（WebSocket 推送 + 邮件，消费突发热点事件） */
    private Notifications notifications = new Notifications();

    @Data
    public static class Newsnow {
        /** newsnow API 地址，如 https://newsnow.busiyi.world/api/s/weibo */
        private String apiUrl = "";
        /** 期望返回数据的域名白名单（参考 TrendRadar 防链接劫持校验） */
        private String expectedDomain = "";
    }

    @Data
    public static class Sixty {
        /** 60s 聚合 API 基地址（微博/知乎/抖音/B站/百度/今日头条） */
        private String apiBase = "https://60s.viki.moe";
        /** 单平台请求超时（毫秒） */
        private long timeoutMs = 10_000;
    }

    @Data
    public static class Analysis {
        /** 是否开启 AI 分析（关闭时接口不附加分析结果，前端不展示） */
        private boolean enabled = true;
        /** 单批分析条数上限（成本控制：默认 10 条/次模型调用） */
        private int batchSize = 10;
        /** 分析结果缓存分钟数（同一关键词+标题在 TTL 内不重复调用） */
        private long cacheTtlMinutes = 1_440;
    }

    @Data
    public static class Burst {
        /** 热度环比涨幅达到该比例（如 0.5=+50%）且上升 → 标记「飙升」 */
        private double heatRatioThreshold = 0.5;
        /** 热度环比涨幅达到该比例（如 0.2=+20%）→ 标记「上升」 */
        private double heatRiseThreshold = 0.2;
        /** 排名上升达到该位数 → 标记「上升」 */
        private int rankRiseThreshold = 5;
    }

    @Data
    public static class Notifications {
        /** 总开关（false 时既不推 WebSocket 也不发邮件） */
        private boolean enabled = true;
        private Email email = new Email();

        @Data
        public static class Email {
            /** 邮件开关（仍需配置 SMTP 与收件人才能真正发送） */
            private boolean enabled = true;
            /** 收件人（逗号分隔；为空则不发送） */
            private String recipients = "";
            /** 发件人地址 */
            private String from = "contentops@localhost";
        }
    }
}
