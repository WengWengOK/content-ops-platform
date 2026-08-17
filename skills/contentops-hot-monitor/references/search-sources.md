# 数据源说明

ContentOps 热点监控采用**多路降级链**（`provider=chain` 默认），保证单个数据源故障不影响整体。

## 数据源与覆盖平台

| 数据源 | 覆盖平台 | 说明 |
|--------|----------|------|
| 60s 聚合 API（[vikiboss/60s](https://github.com/vikiboss/60s)） | 微博 / 知乎 / 抖音 / B站 / 百度 / 今日头条 | 主数据源，无需 Key；有调用频率限制（429 时自动降级） |
| B站官方排行榜 API | B站 | 60s 的 B站源不稳定时自动兜底（`/x/web-interface/ranking`） |
| newsnow 聚合 API | 小红书 + 全平台 | 小红书数据入口；官方站可能被 Cloudflare 风控（403），可配置可达镜像 |
| Tavily 搜索（可选） | 全网 + 新闻 | 配置 `TAVILY_API_KEY` 后，`/web-search` 自动聚合全网/新闻结果 |
| mock（演示） | 全平台 | 仅 `provider=mock` 时启用，不含真实数据 |

## 安全与质量

- **域名白名单**：60s/B站 等来源的链接按平台域名校验（如 `s.weibo.com`、`zhihu.com`、`bilibili.com`），非白名单链接直接丢弃，防链接劫持
- **摘要截断**：长摘要（如知乎详情）截断到 1000 字，避免超长文本入库
- **AI 分析**：默认开启相关性/可信度/摘要（可关闭省成本），模型失败自动降级不影响列表

## 已知限制

1. **小红书**：无免费公开热榜 API。需配置可达的 newsnow 镜像
   （`CONTENTOPS_TREND_NEWSON_API_URL`）或接入 Playwright 爬虫（P1 路线）
2. **60s 限流**：短时间内频繁请求会返回 429，链式降级到下一数据源；轮询间隔默认 30 分钟
3. **newsnow 风控**：官方站 `newsnow.busiyi.world` 部分网络环境被 Cloudflare 拦截
4. **热度口径**：各平台字段不同（微博 hot_value / B站 play / 百度 score），仅用于排序参考

## 配置入口

```yaml
contentops:
  trend:
    provider: chain              # chain | sixty | newsnow | mock
    newsnow:
      api-url: https://newsnow.busiyi.world/api/s/{platform}   # {platform} 占位符
    sixty:
      api-base: https://60s.viki.moe
tavily:
  api-key: ${TAVILY_API_KEY:}    # 全网搜索
```
