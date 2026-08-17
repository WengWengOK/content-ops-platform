/**
 * 热点监控 API —— 独立模块：选题模块/前端直接取热点生成作品。
 */
import { apiClient } from './client'
import type {
  AgentResponse,
  TrendBurstEvent,
  TrendHotspot,
  TrendKeywordHit,
  TrendHistoryPoint,
  TrendPlatformHeat,
  TrendSubscription,
  WebSearchHit,
} from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** GET /trends — 获取最新热点列表（watch=true 只看我关注的方向） */
export async function listTrends(
  platform?: string,
  limit?: number,
  watch = false,
  burst = false,
  timeRange = 'latest'
): Promise<TrendHotspot[]> {
  const { data } = await apiClient.get<AgentResponse<{ total: number; hotspots: TrendHotspot[] }>>(
    '/trends',
    { params: { platform, limit, watch, burst, timeRange } }
  )
  return unwrap(data).hotspots ?? []
}

/** POST /trends/refresh — 立即刷新热点快照 */
export async function refreshTrends(): Promise<{ refreshed: boolean; captured: number }> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>('/trends/refresh')
  const d = unwrap(data)
  return {
    refreshed: Boolean(d.refreshed),
    captured: Number(d.captured ?? 0),
  }
}

/** GET /trends/platforms — 支持的热榜平台列表 */
export async function listTrendPlatforms(): Promise<string[]> {
  const { data } = await apiClient.get<AgentResponse<string[]>>('/trends/platforms')
  return unwrap(data)
}

/** GET /trends/subscriptions — 我的监控方向 */
export async function listTrendSubscriptions(): Promise<TrendSubscription[]> {
  const { data } = await apiClient.get<AgentResponse<TrendSubscription[]>>('/trends/subscriptions')
  return unwrap(data)
}

/** POST /trends/subscriptions — 新增监控方向 */
export async function addTrendSubscription(keyword: string): Promise<TrendSubscription> {
  const { data } = await apiClient.post<AgentResponse<TrendSubscription>>(
    '/trends/subscriptions',
    { keyword }
  )
  return unwrap(data)
}

/** DELETE /trends/subscriptions/{id} — 删除监控方向 */
export async function removeTrendSubscription(subscriptionId: string): Promise<void> {
  await apiClient.delete(`/trends/subscriptions/${subscriptionId}`)
}

/** PUT /trends/subscriptions/{id}/enabled — 启用/暂停监控方向 */
export async function setTrendSubscriptionEnabled(
  subscriptionId: string,
  enabled: boolean
): Promise<void> {
  await apiClient.put(`/trends/subscriptions/${subscriptionId}/enabled`, { enabled })
}

/** GET /trends/search — 关键词驱动抓取：跨平台搜索热点（按热度排序） */
export async function searchTrends(
  q: string,
  platform?: string,
  limit?: number
): Promise<TrendHotspot[]> {
  const { data } = await apiClient.get<AgentResponse<{ total: number; hotspots: TrendHotspot[] }>>(
    '/trends/search',
    { params: { q, platform, limit } }
  )
  return unwrap(data).hotspots ?? []
}

/** GET /trends/keyword-hits — 最近的关键词命中记录 */
export async function listKeywordHits(
  keyword?: string,
  limit?: number,
  timeRange = 'latest'
): Promise<TrendKeywordHit[]> {
  const { data } = await apiClient.get<AgentResponse<{ total: number; hits: TrendKeywordHit[] }>>(
    '/trends/keyword-hits',
    { params: { keyword, limit, timeRange } }
  )
  return unwrap(data).hits ?? []
}

/** GET /trends/bursts — 最近突发热点事件（新上榜/飙升/上升） */
export async function listBurstEvents(
  platform?: string,
  limit?: number,
  timeRange = 'latest'
): Promise<TrendBurstEvent[]> {
  const { data } = await apiClient.get<AgentResponse<{ total: number; bursts: TrendBurstEvent[] }>>(
    '/trends/bursts',
    { params: { platform, limit, timeRange } }
  )
  return unwrap(data).bursts ?? []
}

/** GET /trends/notifications/status — 实时通知状态 */
export async function getNotificationStatus(): Promise<{
  enabled: boolean
  wsConnected: number
  emailConfigured: boolean
}> {
  const { data } = await apiClient.get<
    AgentResponse<{ enabled: boolean; wsConnected: number; emailConfigured: boolean }>
  >('/trends/notifications/status')
  return unwrap(data)
}

/** GET /trends/web-search — 全网搜索（热榜内 + Tavily 全网/新闻聚合） */
export async function webSearch(
  q: string,
  platform?: string,
  limit?: number
): Promise<{
  hotspots: TrendHotspot[]
  web: WebSearchHit[]
  webAvailable: boolean
}> {
  const { data } = await apiClient.get<
    AgentResponse<{ hotspots: TrendHotspot[]; web: WebSearchHit[]; webAvailable: boolean }>
  >('/trends/web-search', { params: { q, platform, limit } })
  return unwrap(data)
}

/** GET /trends/history — 主题趋势（热度曲线 + 平台对比 + 上榜时长） */
export async function trendHistory(
  title: string,
  platform?: string,
  hours = 24
): Promise<{
  title: string
  platform?: string
  points: TrendHistoryPoint[]
  platforms: TrendPlatformHeat[]
  firstSeenAt?: string
  uptimeHours: number
}> {
  const { data } = await apiClient.get<AgentResponse<{
    title: string
    platform?: string
    points: TrendHistoryPoint[]
    platforms: TrendPlatformHeat[]
    firstSeenAt?: string
    uptimeHours: number
  }>>('/trends/history', { params: { title, platform, hours } })
  return unwrap(data)
}
