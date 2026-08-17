package com.contentops.trend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内置热点数据源（开发/演示）：无外部依赖即可看到多平台热榜，供选题模块联调。
 * 通过 {@code contentops.trend.provider=mock}（默认）启用。
 */
@Slf4j
@Component
@Order(3)
public class MockTrendProvider implements TrendProvider {

    private static final Map<String, String> PLATFORM_NAMES = Map.of(
            "xiaohongshu", "小红书",
            "weibo", "微博",
            "douyin", "抖音",
            "bilibili", "哔哩哔哩",
            "zhihu", "知乎");

    private static final Map<String, List<Object[]>> HOT_LISTS = Map.of(
            "xiaohongshu", List.of(
                    new Object[]{"为什么这届年轻人开始流行“极简生活”", "生活方式", 125000L},
                    new Object[]{"3 个让你效率翻倍的时间管理方法", "职场", 98000L},
                    new Object[]{"春日氛围感穿搭公式，直接抄作业", "时尚", 87000L},
                    new Object[]{"自制低卡早餐合集，一周不重样", "美食", 76000L},
                    new Object[]{"普通人如何用 AI 工具做副业？", "科技", 69000L}),
            "weibo", List.of(
                    new Object[]{"国产 AI 大模型新一轮升级引热议", "科技", 230000L},
                    new Object[]{"城市夜间经济新玩法登上热搜", "社会", 185000L},
                    new Object[]{"高考倒计时：考生和家长如何减压", "教育", 160000L},
                    new Object[]{"新能源车降价潮持续", "财经", 142000L},
                    new Object[]{"全民健身日掀起运动打卡热潮", "体育", 118000L}),
            "douyin", List.of(
                    new Object[]{"挑战一周不点外卖，省了多少？", "生活方式", 205000L},
                    new Object[]{"沉浸式整理书桌，治愈强迫症", "生活", 176000L},
                    new Object[]{"5 分钟搞懂大模型微调", "科技", 132000L},
                    new Object[]{"露营装备避坑指南", "户外", 98000L},
                    new Object[]{"打工人通勤路上的效率神器", "职场", 88000L}),
            "bilibili", List.of(
                    new Object[]{"【硬核科普】时间到底存不存在？", "科普", 150000L},
                    new Object[]{"我花 30 天把效率提升了一倍", "职场", 121000L},
                    new Object[]{"这届网友的年度书单有多卷", "读书", 93000L},
                    new Object[]{"独立开发者一年赚了多少？", "科技", 84000L},
                    new Object[]{"实测 10 款 AI 写作工具", "科技", 71000L}),
            "zhihu", List.of(
                    new Object[]{"如何评价 2026 年上半年 AI 应用落地趋势？", "科技", 88000L},
                    new Object[]{"年轻人到底该不该提前还房贷？", "财经", 76000L},
                    new Object[]{"有哪些让你坚持很久的好习惯？", "成长", 65000L},
                    new Object[]{"如何看待“县城文学”走红？", "文化", 59000L},
                    new Object[]{"程序员 35 岁之后都去哪了？", "职场", 52000L}));

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public List<String> supportedPlatforms() {
        return new ArrayList<>(HOT_LISTS.keySet());
    }

    @Override
    public List<TrendHotspot> fetchHotspots(String platform, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<TrendHotspot> result = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> entry : HOT_LISTS.entrySet()) {
            if (platform != null && !platform.isBlank() && !entry.getKey().equals(platform)) {
                continue;
            }
            List<Object[]> items = entry.getValue();
            int count = Math.min(items.size(), Math.max(1, limit));
            for (int i = 0; i < count; i++) {
                Object[] item = items.get(i);
                result.add(TrendHotspot.builder()
                        .id(UUID.randomUUID().toString())
                        .platform(entry.getKey())
                        .title(String.valueOf(item[0]))
                        .category(String.valueOf(item[1]))
                        .heat((Long) item[2])
                        .rank(i + 1)
                        .url("https://example.com/trend/" + entry.getKey() + "/" + (i + 1))
                        .summary(PLATFORM_NAMES.get(entry.getKey()) + "热榜第 " + (i + 1) + " 名")
                        .capturedAt(now)
                        .build());
            }
        }
        log.debug("[Trend] mock provider fetched {} hotspots (platform={}, limit={})",
                result.size(), platform, limit);
        return result;
    }
}
