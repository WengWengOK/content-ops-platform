---
name: contentops-hot-monitor
description: 通过 ContentOps 热点监控服务获取多平台实时热榜、关键词搜索、全网聚合、突发热点事件与主题趋势，供 AI Agent 选题、追热点、内容创作使用。Use when users want real-time trending topics, burst detection, keyword monitoring, or trend analysis for Chinese social platforms (微博/知乎/抖音/B站/百度/今日头条/小红书).
license: MIT
metadata:
  author: ContentOps
  version: "1.0"
  api_base: http://localhost:8080/api/v1/trends
  tags:
    - trending
    - social-media
    - hot-search
    - burst-detection
    - content-ops
---

# ContentOps 热点监控技能包

让 AI Agent 直接使用内容平台的实时热点能力：多平台热榜、关键词搜索、全网聚合、
突发热点检测、主题趋势。适用于选题规划、追热点、热点分析报告等任务。

## 何时使用

- 用户问「现在微博/知乎/抖音/B站上什么最火」
- 需要为某关键词/行业找近期热点（搜索 + 全网聚合）
- 需要知道哪些话题在爆发（新上榜 / 飙升 / 上升）
- 需要某个主题的热度曲线、跨平台对比、上榜时长
- 需要为内容创作选题提供热点依据

## API 一览（Base：http://localhost:8080/api/v1/trends）

| 能力 | 端点 | 说明 |
|------|------|------|
| 实时热榜 | `GET /trends?platform=&limit=&timeRange=` | platform: weibo/zhihu/douyin/bilibili/baidu/toutiao/xiaohongshu；timeRange: latest/1h/24h/7d |
| 只看突发 | `GET /trends?burst=true` | 仅返回 新上榜/飙升/上升 条目 |
| 只看关注 | `GET /trends?watch=true` | 仅返回用户已启用监控方向匹配的条目 |
| 关键词搜索 | `GET /trends/search?q=&platform=&limit=` | 热榜快照内搜索，按热度排序 |
| 全网搜索 | `GET /trends/web-search?q=&platform=&limit=` | 热榜内搜索 + Tavily 全网/新闻聚合（需 TAVILY_API_KEY） |
| 突发热点事件 | `GET /trends/bursts?platform=&limit=&timeRange=` | 历史爆发事件（含已下榜的） |
| 关键词命中 | `GET /trends/keyword-hits?keyword=&limit=&timeRange=` | 已启用监控方向的命中记录 |
| 主题趋势 | `GET /trends/history?title=&platform=&hours=` | 热度曲线 + 跨平台对比 + 上榜时长 |
| 平台列表 | `GET /trends/platforms` | 支持的热榜平台 |
| 监控方向 | `GET/POST /trends/subscriptions`、`PUT /subscriptions/{id}/enabled`、`DELETE /subscriptions/{id}` | 关键词启停管理 |

## 常用调用示例

```bash
# 各平台实时热榜 TOP 5
curl "http://localhost:8080/api/v1/trends?limit=30"

# 只看近 24h 内爆发的话题
curl "http://localhost:8080/api/v1/trends?burst=true&timeRange=24h&limit=20"

# 关键词全网搜索（含全网/新闻源）
curl "http://localhost:8080/api/v1/trends/web-search?q=胖东来&limit=10"

# 某主题的趋势曲线 + 平台对比 + 上榜时长
curl "http://localhost:8080/api/v1/trends/history?title=欢天喜地七仙女选角让人笑哭&platform=weibo&hours=24"

# 最近突发事件
curl "http://localhost:8080/api/v1/trends/bursts?timeRange=24h&limit=20"
```

## 响应字段说明

热点条目（TrendHotspot）关键字段：

- `burstLabel`：`新上榜`（首次出现）/ `飙升`（热度环比 +50% 以上）/ `上升`（+20% 或排名 +5 以上），空表示平稳或回落
- `heatDelta` / `rankDelta`：较上一快照的热度增量 / 排名上升数
- `burstScore`：爆发得分（涨幅折算 + 排名加权），越高越值得关注
- `firstSeenAt`：主题首次出现时间（上榜时长可由此计算）
- `analysis`：AI 分析（relevance 相关度 / credibility 可信度 / riskFlag 疑似夸大 / summary 摘要）

## 最佳实践

1. **选题**：优先选 `新上榜` 抢首发、`飙升` 借势、`上升` 跟进；注意 `analysis.riskFlag=true` 的条目谨慎使用
2. **频率**：数据每 30 分钟轮询刷新一次；追实时用 `burst=true`，做盘点用 `timeRange=24h/7d`
3. **平台差异**：热度口径各平台不同（微博热度值 / B站播放量 / 百度评分），仅做排序参考，勿跨平台直接比较数值
4. **降级**：单个数据源不可用不影响整体（多路降级链）；AI 分析与 Tavily 未配置时接口照常返回，只是缺少对应字段

## 故障排查

- 接口返回空：数据源暂不可达（60s 限流 / newsnow 风控），稍后重试或换平台
- 小红书无数据：需要配置可达的 newsnow 镜像（`CONTENTOPS_TREND_NEWSON_API_URL`）或接入 Playwright 爬虫
- 全网搜索只有热榜结果：`TAVILY_API_KEY` 未配置
- 详细数据源说明见 `references/search-sources.md`，突发解读见 `references/analysis-guide.md`
