import { useCallback, useEffect, useState } from 'react'
import { Layout } from '@/components/layout/Layout'
import {
  listTrends,
  refreshTrends,
  listTrendPlatforms,
  listTrendSubscriptions,
  addTrendSubscription,
  removeTrendSubscription,
  setTrendSubscriptionEnabled,
  searchTrends,
  listKeywordHits,
  listBurstEvents,
  getNotificationStatus,
  webSearch,
} from '@/api/trends'
import type { TrendBurstEvent, TrendHotspot, TrendKeywordHit, TrendSubscription, WebSearchHit } from '@/types'
import { TrendHistoryPanel } from '@/components/trends/TrendHistoryPanel'

const PLATFORM_NAMES: Record<string, string> = {
  xiaohongshu: '小红书',
  weibo: '微博',
  douyin: '抖音',
  bilibili: '哔哩哔哩',
  zhihu: '知乎',
  baidu: '百度',
  toutiao: '今日头条',
}
const FALLBACK_PLATFORMS: { code: string; name: string }[] = [
  { code: 'weibo', name: '微博' },
  { code: 'zhihu', name: '知乎' },
  { code: 'douyin', name: '抖音' },
  { code: 'bilibili', name: '哔哩哔哩' },
  { code: 'baidu', name: '百度' },
  { code: 'toutiao', name: '今日头条' },
]
const TIME_RANGES: { code: string; label: string }[] = [
  { code: 'latest', label: '实时' },
  { code: '1h', label: '近1小时' },
  { code: '24h', label: '近24小时' },
  { code: '7d', label: '近7天' },
]

export function TrendsPage() {
  const [platform, setPlatform] = useState('')
  const [watchOnly, setWatchOnly] = useState(false)
  const [hotspots, setHotspots] = useState<TrendHotspot[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const [subscriptions, setSubscriptions] = useState<TrendSubscription[]>([])
  const [keywordInput, setKeywordInput] = useState('')
  const [toast, setToast] = useState<{ msg: string; color: string } | null>(null)
  const [platforms, setPlatforms] = useState(FALLBACK_PLATFORMS)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState<TrendHotspot[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [hits, setHits] = useState<TrendKeywordHit[]>([])
  const [burstOnly, setBurstOnly] = useState(false)
  const [burstEvents, setBurstEvents] = useState<TrendBurstEvent[]>([])
  const [wsConnected, setWsConnected] = useState(false)
  const [emailConfigured, setEmailConfigured] = useState(false)
  const [timeRange, setTimeRange] = useState('latest')
  const [searchWeb, setSearchWeb] = useState<WebSearchHit[]>([])
  const [searchWebAvailable, setSearchWebAvailable] = useState(false)
  const [selectedTrend, setSelectedTrend] = useState<{ title: string; platform: string } | null>(null)

  const showToast = (msg: string, color = '#165DFF') => {
    setToast({ msg, color })
    setTimeout(() => setToast(null), 2600)
  }

  const loadSubscriptions = useCallback(async () => {
    try {
      setSubscriptions(await listTrendSubscriptions())
    } catch {
      setSubscriptions([])
    }
  }, [])

  const loadHits = useCallback(async () => {
    try {
      setHits(await listKeywordHits(undefined, 10, timeRange))
    } catch {
      setHits([])
    }
  }, [timeRange])

  const loadBurstEvents = useCallback(async () => {
    try {
      setBurstEvents(await listBurstEvents(undefined, 8, timeRange))
    } catch {
      setBurstEvents([])
    }
  }, [timeRange])

  useEffect(() => {
    void loadSubscriptions()
    void loadHits()
    void loadBurstEvents()
  }, [loadSubscriptions, loadHits, loadBurstEvents])

  useEffect(() => {
    listTrendPlatforms()
      .then((codes) => {
        const mapped = codes.map((c) => ({ code: c, name: PLATFORM_NAMES[c] ?? c }))
        if (mapped.length > 0) setPlatforms(mapped)
      })
      .catch(() => {
        /* 后端不可达时使用兜底平台列表 */
      })
  }, [])

  // 实时通知：连接 /ws/trends，收到突发热点事件立即提示并置顶展示
  useEffect(() => {
    let ws: WebSocket | null = null
    let retryTimer: number | undefined
    let closed = false

    const scheduleRetry = () => {
      if (!closed) retryTimer = window.setTimeout(connect, 5000)
    }
    const connect = () => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const url =
        (import.meta.env.VITE_WS_URL as string | undefined) ||
        `${proto}://${location.hostname}:8080/ws/trends`
      try {
        ws = new WebSocket(url)
      } catch {
        scheduleRetry()
        return
      }
      ws.onopen = () => setWsConnected(true)
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data)
          if (msg.type === 'bursts' && Array.isArray(msg.data) && msg.data.length > 0) {
            setBurstEvents((prev) => [...msg.data, ...prev].slice(0, 20))
            showToast(`🔥 检测到 ${msg.data.length} 条突发热点`, '#F53F3F')
          }
        } catch {
          /* 忽略无法解析的推送 */
        }
      }
      ws.onclose = () => {
        setWsConnected(false)
        scheduleRetry()
      }
      ws.onerror = () => ws?.close()
    }

    connect()
    return () => {
      closed = true
      if (retryTimer) window.clearTimeout(retryTimer)
      ws?.close()
    }
  }, [])

  useEffect(() => {
    getNotificationStatus()
      .then((s) => setEmailConfigured(Boolean(s.emailConfigured)))
      .catch(() => setEmailConfigured(false))
  }, [])

  const loadHotspots = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setHotspots(await listTrends(platform || undefined, 30, watchOnly, burstOnly, timeRange))
    } catch (err: any) {
      setError(err?.message || '热点加载失败')
      setHotspots([])
    } finally {
      setLoading(false)
    }
  }, [platform, watchOnly, burstOnly, timeRange])

  useEffect(() => {
    void loadHotspots()
  }, [loadHotspots])

  const handleAddKeyword = async () => {
    const keyword = keywordInput.trim()
    if (!keyword) return
    try {
      await addTrendSubscription(keyword)
      setKeywordInput('')
      await loadSubscriptions()
      if (watchOnly) await loadHotspots()
      showToast(`已添加监控方向：${keyword}`, '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '添加失败', '#F53F3F')
    }
  }

  const handleRemoveKeyword = async (id: string) => {
    try {
      await removeTrendSubscription(id)
      await loadSubscriptions()
      if (watchOnly) await loadHotspots()
      showToast('监控方向已删除', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '删除失败', '#F53F3F')
    }
  }

  const handleToggleKeyword = async (sub: TrendSubscription) => {
    try {
      await setTrendSubscriptionEnabled(sub.subscriptionId, !sub.enabled)
      await loadSubscriptions()
      await loadHits()
      if (watchOnly) await loadHotspots()
      showToast(sub.enabled ? `已暂停监控：${sub.keyword}` : `已启用监控：${sub.keyword}`, '#165DFF')
    } catch (err: any) {
      showToast(err?.message || '切换失败', '#F53F3F')
    }
  }

  const handleSearch = async () => {
    const q = searchQuery.trim()
    if (!q) return
    setSearching(true)
    try {
      const res = await webSearch(q, platform || undefined, 30)
      setSearchResults(res.hotspots)
      setSearchWeb(res.web ?? [])
      setSearchWebAvailable(res.webAvailable)
    } catch (err: any) {
      showToast(err?.message || '搜索失败', '#F53F3F')
      setSearchResults([])
      setSearchWeb([])
    } finally {
      setSearching(false)
    }
  }

  const clearSearch = () => {
    setSearchQuery('')
    setSearchResults(null)
    setSearchWeb([])
  }

  const handleRefresh = async () => {
    try {
      const r = await refreshTrends()
      showToast(`热点已刷新（${r.captured} 条）`, '#00B42A')
      await loadHotspots()
      await loadBurstEvents()
    } catch (err: any) {
      showToast(err?.message || '刷新失败', '#F53F3F')
    }
  }

  return (
    <Layout activeNav="trends" breadcrumbs={[{ label: '热点监控' }]}>
      <div className="mx-auto max-w-6xl p-6">
        {/* 头部 */}
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold" style={{ color: '#1D2129' }}>热点监控</h1>
            <p className="mt-1 text-sm" style={{ color: '#86909C' }}>
              自定义监控行业方向，多平台热榜聚合，点击标题直达原文
            </p>
          </div>
          <div className="flex items-center gap-3">
            <span className="flex items-center gap-1.5 text-xs" style={{ color: wsConnected ? '#00B42A' : '#F53F3F' }}>
              <span
                className="inline-block h-2 w-2 rounded-full"
                style={{ background: wsConnected ? '#00B42A' : '#F53F3F' }}
              />
              {wsConnected ? '实时连接中' : '实时未连接'}
              {emailConfigured && ' · 邮件已配置'}
            </span>
            <button
              onClick={handleRefresh}
              disabled={loading}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors"
              style={{ background: loading ? '#A0CFFF' : '#165DFF', border: 'none', cursor: loading ? 'not-allowed' : 'pointer' }}
            >
              {loading ? '加载中…' : '刷新热点'}
            </button>
          </div>
        </div>

        {/* 监控方向管理 */}
        <div className="card mb-6 p-5">
          <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>
            监控方向（行业 / 关键词）
          </h2>
          <div className="flex items-center gap-2">
            <input
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAddKeyword()}
              placeholder="如：AI、新能源、职场、时间管理…"
              className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none transition-all focus:ring-2 focus:ring-[#165DFF]"
              style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
            />
            <button
              onClick={handleAddKeyword}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white"
              style={{ background: '#FF5C47', border: 'none', cursor: 'pointer' }}
            >
              + 添加方向
            </button>
          </div>
          {subscriptions.length === 0 ? (
            <p className="mt-3 text-xs" style={{ color: '#C9CDD4' }}>
              还没有监控方向，添加后开启「只看我关注的方向」即可精准过滤热点
            </p>
          ) : (
            <div className="mt-3 flex flex-wrap gap-2">
              {subscriptions.map((s) => (
                <span
                  key={s.subscriptionId}
                  className="inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-medium"
                  style={{
                    background: s.enabled === false ? '#F2F3F5' : '#FFF0F5',
                    color: s.enabled === false ? '#86909C' : '#C40E3A',
                  }}
                >
                  {s.keyword}
                  <button
                    onClick={() => handleToggleKeyword(s)}
                    className="hover:opacity-60"
                    style={{ background: 'none', border: 'none', cursor: 'pointer' }}
                    title={s.enabled === false ? '点击启用监控' : '点击暂停监控'}
                  >
                    {s.enabled === false ? '⚪ 暂停' : '🟢 监控中'}
                  </button>
                  <button
                    onClick={() => handleRemoveKeyword(s.subscriptionId)}
                    className="text-[#C40E3A] hover:opacity-60"
                    style={{ background: 'none', border: 'none', cursor: 'pointer' }}
                    aria-label={`删除 ${s.keyword}`}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
          <label className="mt-4 flex cursor-pointer items-center gap-2 text-sm" style={{ color: '#4E5969' }}>
            <input
              type="checkbox"
              checked={watchOnly}
              onChange={(e) => setWatchOnly(e.target.checked)}
              className="h-4 w-4 cursor-pointer"
              style={{ accentColor: '#165DFF' }}
            />
            只看我关注的方向
          </label>
          <label className="mt-2 flex cursor-pointer items-center gap-2 text-sm" style={{ color: '#4E5969' }}>
            <input
              type="checkbox"
              checked={burstOnly}
              onChange={(e) => setBurstOnly(e.target.checked)}
              className="h-4 w-4 cursor-pointer"
              style={{ accentColor: '#FF5C47' }}
            />
            只看突发热点（新上榜 / 飙升 / 上升）
          </label>
        </div>

        {/* 关键词驱动抓取（跨平台搜索） */}
        <div className="card mb-6 p-5">
          <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>
            全网搜索（热榜 + 全网聚合）
          </h2>
          <div className="flex items-center gap-2">
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="输入关键词，如：AI、新能源、胖东来…"
              className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none transition-all focus:ring-2 focus:ring-[#165DFF]"
              style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
            />
            <button
              onClick={handleSearch}
              disabled={searching}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white"
              style={{ background: searching ? '#A0CFFF' : '#165DFF', border: 'none', cursor: searching ? 'not-allowed' : 'pointer' }}
            >
              {searching ? '搜索中…' : '搜索'}
            </button>
            {searchResults !== null && (
              <button
                onClick={clearSearch}
                className="rounded-lg px-3 py-2 text-sm font-medium"
                style={{ background: '#F2F3F5', color: '#4E5969', border: 'none', cursor: 'pointer' }}
              >
                清除
              </button>
            )}
          </div>
          {searchResults !== null && (
            <p className="mt-2 text-xs" style={{ color: '#86909C' }}>
              共找到 {searchResults.length} 条与「{searchQuery}」相关的热点（按热度排序）
            </p>
          )}
          {searchResults !== null && !searchWebAvailable && (
            <p className="mt-1 text-xs" style={{ color: '#C9CDD4' }}>
              ⓘ 配置 TAVILY_API_KEY 后自动聚合全网与新闻源，当前为平台热榜内搜索
            </p>
          )}
          {searchWeb.length > 0 && (
            <div className="mt-3 space-y-1.5 border-t pt-3" style={{ borderColor: '#F2F3F5' }}>
              <p className="text-xs font-semibold" style={{ color: '#4E5969' }}>全网 / 新闻源（{searchWeb.length}）</p>
              {searchWeb.map((w, i) => (
                <div key={i} className="flex items-start gap-2 text-xs">
                  <span
                    className="mt-0.5 flex-shrink-0 rounded px-1.5 py-0.5 font-medium"
                    style={{
                      background: w.source === 'tavily-news' ? '#FFF7E8' : '#E8F7FF',
                      color: w.source === 'tavily-news' ? '#FF7D00' : '#165DFF',
                    }}
                  >
                    {w.source === 'tavily-news' ? '新闻' : '全网'}
                  </span>
                  <span className="min-w-0">
                    {w.url ? (
                      <a href={w.url} target="_blank" rel="noreferrer" className="font-medium hover:underline" style={{ color: '#1D2129' }}>
                        {w.title || w.url}
                      </a>
                    ) : (
                      <span style={{ color: '#1D2129' }}>{w.title}</span>
                    )}
                    {w.content && (
                      <span className="mt-0.5 block truncate" style={{ color: '#86909C' }}>{w.content}</span>
                    )}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 关键词命中记录 */}
        {hits.length > 0 && (
          <div className="card mb-6 p-5">
            <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>
              最近命中记录
            </h2>
            <div className="space-y-1.5">
              {hits.map((h) => (
                <div key={h.hitId} className="flex items-center justify-between gap-3 text-xs">
                  <span className="min-w-0 truncate" style={{ color: '#4E5969' }}>
                    <b style={{ color: '#C40E3A' }}>{h.keyword}</b>
                    {' · '}
                    {PLATFORM_NAMES[h.platform] ?? h.platform}
                    {' · '}
                    {h.url ? (
                      <a href={h.url} target="_blank" rel="noreferrer" className="hover:underline" style={{ color: '#165DFF' }}>
                        {h.title}
                      </a>
                    ) : (
                      h.title
                    )}
                  </span>
                  {h.heat != null && <span style={{ color: '#86909C' }}>热度 {h.heat}</span>}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 突发热点事件 */}
        {burstEvents.length > 0 && (
          <div className="card mb-6 p-5">
            <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>
              最近突发（新上榜 / 飙升 / 上升）
            </h2>
            <div className="space-y-1.5">
              {burstEvents.map((e) => (
                <div key={e.eventId} className="flex items-center justify-between gap-3 text-xs">
                  <span className="min-w-0 truncate" style={{ color: '#4E5969' }}>
                    <b
                      style={{
                        color: e.burstLabel === '飙升' ? '#F53F3F' : e.burstLabel === '新上榜' ? '#00B42A' : '#FF7D00',
                      }}
                    >
                      {e.burstLabel === '飙升' ? '🔥 飙升' : e.burstLabel === '新上榜' ? '🆕 新上榜' : '↑ 上升'}
                    </b>
                    {' · '}
                    {PLATFORM_NAMES[e.platform] ?? e.platform}
                    {' · '}
                    {e.url ? (
                      <a href={e.url} target="_blank" rel="noreferrer" className="hover:underline" style={{ color: '#165DFF' }}>
                        {e.title}
                      </a>
                    ) : (
                      e.title
                    )}
                  </span>
                  <span className="flex-shrink-0" style={{ color: '#86909C' }}>
                    {e.heatDelta != null && e.heatDelta > 0 && `热度 +${e.heatDelta.toLocaleString()} `}
                    {e.rankDelta != null && e.rankDelta > 0 && `排名 ↑${e.rankDelta}`}
                    {e.capturedAt && ` · ${e.capturedAt.slice(5, 16)}`}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 时间范围筛选 */}
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <span className="text-xs font-medium" style={{ color: '#86909C' }}>时间范围</span>
          {TIME_RANGES.map((t) => (
            <button
              key={t.code}
              onClick={() => setTimeRange(t.code)}
              className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
              style={{
                background: timeRange === t.code ? '#165DFF' : '#F2F3F5',
                color: timeRange === t.code ? '#fff' : '#4E5969',
                border: 'none',
                cursor: 'pointer',
              }}
            >
              {t.label}
            </button>
          ))}
          {timeRange !== 'latest' && (
            <span className="text-xs" style={{ color: '#86909C' }}>
              显示该时间段内出现在热榜的主题（含已下榜，按平台+标题去重）
            </span>
          )}
        </div>

        {/* 平台 Tab */}
        <div className="mb-4 flex flex-wrap gap-2">
          <button
            onClick={() => setPlatform('')}
            className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
            style={{
              background: platform === '' ? '#165DFF' : '#F2F3F5',
              color: platform === '' ? '#fff' : '#4E5969',
              border: 'none',
              cursor: 'pointer',
            }}
          >
            全部
          </button>
          {platforms.map((p) => (
            <button
              key={p.code}
              onClick={() => setPlatform(p.code)}
              className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
              style={{
                background: platform === p.code ? '#FF5C47' : '#F2F3F5',
                color: platform === p.code ? '#fff' : '#4E5969',
                border: 'none',
                cursor: 'pointer',
              }}
            >
              {p.name}
            </button>
          ))}
        </div>

        {/* 热点列表 */}
        {selectedTrend && (
          <TrendHistoryPanel
            title={selectedTrend.title}
            platform={selectedTrend.platform}
            onClose={() => setSelectedTrend(null)}
          />
        )}
        {error && (
          <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: '#F53F3F', color: '#F53F3F', background: '#FFF0F0' }}>
            {error}
          </div>
        )}
        {loading ? (
          <div className="py-16 text-center text-sm" style={{ color: '#86909C' }}>加载热点…</div>
        ) : (searchResults ?? hotspots).length === 0 ? (
          <div className="card py-16 text-center">
            <p className="text-sm" style={{ color: '#86909C' }}>
              {searchResults !== null
                ? '未找到匹配热点，换个关键词试试'
                : watchOnly
                  ? '当前监控方向暂无匹配热点'
                  : burstOnly
                    ? '当前暂无突发热点，稍后刷新再看'
                    : timeRange !== 'latest'
                      ? '该时间范围内暂无热点数据'
                      : '该平台暂无热点数据'}
            </p>
            <p className="mt-2 text-xs" style={{ color: '#C9CDD4' }}>
              {searchResults !== null
                ? '关键词可尝试更短或更通用'
                : watchOnly
                  ? '可添加更多监控方向，或关闭「只看我关注的方向」'
                  : burstOnly
                    ? '突发热点需要至少两轮快照才能对比出涨幅'
                    : timeRange !== 'latest'
                      ? '可尝试切换平台或拉长时间范围'
                      : '可切换平台或点击右上角刷新'}
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            {(searchResults ?? hotspots).map((h) => (
              <div
                key={h.id}
                className="card flex items-center justify-between gap-3 p-4 transition-all hover:shadow-sm"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span
                      className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-xs font-bold text-white"
                      style={{ background: '#FF2D5E' }}
                    >
                      {h.rank ?? '·'}
                    </span>
                    {h.url ? (
                      <a
                        href={h.url}
                        target="_blank"
                        rel="noreferrer"
                        className="truncate text-sm font-medium transition-colors hover:underline"
                        style={{ color: '#1D2129' }}
                        title="点击打开原文"
                      >
                        {h.title}
                        <span className="ml-1 text-xs" style={{ color: '#165DFF' }}>↗</span>
                      </a>
                    ) : (
                      <span className="truncate text-sm font-medium" style={{ color: '#1D2129' }}>
                        {h.title}
                      </span>
                    )}
                    <button
                      onClick={() => setSelectedTrend({ title: h.title, platform: h.platform })}
                      className="ml-1 flex-shrink-0 rounded px-1.5 py-0.5 text-xs font-medium transition-colors hover:opacity-70"
                      style={{ background: '#F2F3F5', color: '#165DFF', border: 'none', cursor: 'pointer' }}
                      title="查看热度曲线 / 平台对比 / 上榜时长"
                    >
                      📈
                    </button>
                  </div>
                  <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs" style={{ color: '#86909C' }}>
                    {h.burstLabel && (
                      <span
                        className="rounded px-2 py-0.5 font-medium"
                        style={{
                          background: h.burstLabel === '飙升' ? '#FFF0F0' : h.burstLabel === '新上榜' ? '#E8FFEA' : '#FFF7E8',
                          color: h.burstLabel === '飙升' ? '#F53F3F' : h.burstLabel === '新上榜' ? '#00B42A' : '#FF7D00',
                        }}
                      >
                        {h.burstLabel === '飙升' ? '🔥 飙升' : h.burstLabel === '新上榜' ? '🆕 新上榜' : '↑ 上升'}
                      </span>
                    )}
                    {h.heatDelta != null && h.heatDelta > 0 && <span>热度 +{h.heatDelta.toLocaleString()}</span>}
                    {h.rankDelta != null && h.rankDelta > 0 && <span>排名 ↑{h.rankDelta}</span>}
                    <span
                      className="rounded px-2 py-0.5"
                      style={{ background: '#F2F3F5', color: '#4E5969' }}
                    >
                      {PLATFORM_NAMES[h.platform] ?? h.platform}
                    </span>
                    {h.category && (
                      <span className="rounded px-2 py-0.5" style={{ background: '#FFF0F5', color: '#C40E3A' }}>
                        {h.category}
                      </span>
                    )}
                    {h.heat != null && <span>热度 {h.heat}</span>}
                    {h.summary && <span className="truncate">{h.summary}</span>}
                  </div>
                  {h.analysis && (
                    <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
                      {h.analysis.relevance != null && (
                        <span className="rounded px-2 py-0.5" style={{ background: '#E8F7FF', color: '#0FC6C2' }}>
                          相关度 {h.analysis.relevance}
                        </span>
                      )}
                      {h.analysis.credibility != null && (
                        <span className="rounded px-2 py-0.5" style={{ background: '#E8F7FF', color: '#165DFF' }}>
                          可信度 {h.analysis.credibility}
                        </span>
                      )}
                      {h.analysis.riskFlag === true && (
                        <span className="rounded px-2 py-0.5" style={{ background: '#FFF0F0', color: '#F53F3F' }}>
                          ⚠️ 疑似夸大/谣言
                        </span>
                      )}
                      {h.analysis.summary && (
                        <span className="truncate" style={{ color: '#4E5969' }}>
                          {h.analysis.summary}
                        </span>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Toast */}
      {toast && (
        <div
          className="fixed right-6 top-6 z-[100] flex items-center gap-2 rounded-lg border px-4 py-3 shadow-lg"
          style={{ background: '#fff', borderColor: toast.color }}
        >
          <span className="text-sm" style={{ color: '#1D2129' }}>{toast.msg}</span>
        </div>
      )}
    </Layout>
  )
}
