/**
 * 评论区 AI 助手 API —— 采集/分析/对话/审核/统计。
 */
import { apiClient } from './client'
import type {
  AgentResponse,
  CommentJobRun,
  CommentSchedulerStatus,
  CommentSourceStatus,
  CommentStats,
  CommentRelationOverview,
  CommentWatch,
  IntentCorpusEntry,
  IntentCorpusStats,
  IntentMatchPreview,
  OpsOverview,
  PlatformComment,
} from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** POST /comments/collect — 采集评论（后端按配置走真实接口或模拟数据源） */
export async function collectComments(
  workId: string,
  platform = 'xiaohongshu',
  credentialId?: string
): Promise<{
  collected: number
  inserted: number
  source: string
  fallbackReason?: string
  comments: PlatformComment[]
}> {
  const { data } = await apiClient.post<AgentResponse<{
    collected: number
    inserted: number
    source: string
    fallbackReason?: string
    comments: PlatformComment[]
  }>>('/comments/collect', { workId, platform, credentialId })
  return unwrap(data)
}

/** GET /comments — 评论列表（平台/作品/意图/情感过滤） */
export async function listComments(params: {
  platform?: string
  workId?: string
  intent?: string
  sentiment?: string
  relation?: string
  limit?: number
}): Promise<{ total: number; comments: PlatformComment[] }> {
  const { data } = await apiClient.get<AgentResponse<{ total: number; comments: PlatformComment[] }>>(
    '/comments',
    { params }
  )
  return unwrap(data)
}

/** GET /comments/stats — 意图/情感统计 */
export async function getCommentStats(platform?: string, workId?: string): Promise<CommentStats> {
  const { data } = await apiClient.get<AgentResponse<CommentStats>>('/comments/stats', {
    params: { platform, workId },
  })
  return unwrap(data)
}

/** POST /comments/analyze-all — 批量分析某作品评论 */
export async function analyzeAllComments(params: {
  workId?: string
  platform?: string
  limit?: number
}): Promise<{ analyzed: number; comments: PlatformComment[] }> {
  const { data } = await apiClient.post<AgentResponse<{
    analyzed: number
    comments: PlatformComment[]
  }>>('/comments/analyze-all', params)
  return unwrap(data)
}

/** POST /comments/{id}/analyze — 单条评论 AI 分析 */
export async function analyzeComment(commentId: string): Promise<PlatformComment> {
  const { data } = await apiClient.post<AgentResponse<PlatformComment>>(
    `/comments/${commentId}/analyze`
  )
  return unwrap(data)
}

/** POST /comments/{id}/reply/chat — 多轮 AI 对话 */
export async function chatCommentReply(
  commentId: string,
  message: string
): Promise<PlatformComment> {
  const { data } = await apiClient.post<AgentResponse<PlatformComment>>(
    `/comments/${commentId}/reply/chat`,
    { message }
  )
  return unwrap(data)
}

/** POST /comments/{id}/approve — 审核通过 */
export async function approveCommentReply(commentId: string): Promise<PlatformComment> {
  const { data } = await apiClient.post<AgentResponse<PlatformComment>>(
    `/comments/${commentId}/approve`
  )
  return unwrap(data)
}

/** POST /comments/{id}/send — 发送回复（已配置真实接口则真实回复，否则模拟） */
export async function sendCommentReply(
  commentId: string,
  xsecToken?: string
): Promise<{ comment: PlatformComment; sendMode: string; message: string }> {
  const { data } = await apiClient.post<AgentResponse<{
    comment: PlatformComment
    sendMode: string
    message: string
  }>>(`/comments/${commentId}/send`, xsecToken ? { xsecToken } : {})
  return unwrap(data)
}

/** PUT /comments/{id}/reply — 人工修改回复内容/状态 */
export async function updateCommentReply(
  commentId: string,
  body: { reply?: string; status?: string }
): Promise<PlatformComment> {
  const { data } = await apiClient.put<AgentResponse<PlatformComment>>(
    `/comments/${commentId}/reply`,
    body
  )
  return unwrap(data)
}

// ═══════════════════════════════════════════════════════════════
//  自动采集监控 + 调度状态 + 数据源诊断
// ═══════════════════════════════════════════════════════════════

/** GET /comments/watches — 我的评论监控作品列表 */
export async function listCommentWatches(): Promise<CommentWatch[]> {
  const { data } = await apiClient.get<AgentResponse<CommentWatch[]>>('/comments/watches')
  return unwrap(data)
}

/** POST /comments/watches — 把作品加入自动采集监控 */
export async function addCommentWatch(body: {
  workId: string
  platform?: string
  workflowId?: string
  autoAnalyze?: boolean
  xsecToken?: string
  credentialId?: string
}): Promise<CommentWatch> {
  const { data } = await apiClient.post<AgentResponse<CommentWatch>>('/comments/watches', body)
  return unwrap(data)
}

/** PUT /comments/watches/{id}/enabled — 启用/暂停监控 */
export async function setCommentWatchEnabled(watchId: string, enabled: boolean): Promise<void> {
  await apiClient.put(`/comments/watches/${watchId}/enabled`, { enabled })
}

/** DELETE /comments/watches/{id} — 移除监控 */
export async function removeCommentWatch(watchId: string): Promise<void> {
  await apiClient.delete(`/comments/watches/${watchId}`)
}

/** POST /comments/watches/{id}/run — 立即采集该作品的新评论 */
export async function runCommentWatch(watchId: string): Promise<{
  watchId: string
  workId: string
  source?: string
  fallbackReason?: string
  collected: number
  inserted: number
  analyzed: number
  error?: string
}> {
  const { data } = await apiClient.post<AgentResponse<{
    watchId: string
    workId: string
    source?: string
    fallbackReason?: string
    collected: number
    inserted: number
    analyzed: number
    error?: string
  }>>(`/comments/watches/${watchId}/run`)
  return unwrap(data)
}

/** POST /comments/watches/run-all — 立即执行一轮批量采集 */
export async function runAllCommentWatches(): Promise<CommentJobRun> {
  const { data } = await apiClient.post<AgentResponse<CommentJobRun>>('/comments/watches/run-all')
  return unwrap(data)
}

/** GET /comments/scheduler — 定时采集状态 */
export async function getCommentScheduler(): Promise<CommentSchedulerStatus> {
  const { data } = await apiClient.get<AgentResponse<CommentSchedulerStatus>>('/comments/scheduler')
  return unwrap(data)
}

/** GET /comments/source-status — 数据源诊断（真实接口是否已配置） */
export async function getCommentSourceStatus(): Promise<CommentSourceStatus> {
  const { data } = await apiClient.get<AgentResponse<CommentSourceStatus>>('/comments/source-status')
  return unwrap(data)
}
// ═══════════════════════════════════════════════════════════════
//  评论通知增量采集（账号级，比按笔记轮询更快发现新评论）
// ═══════════════════════════════════════════════════════════════

/** POST /comments/notifications/collect — 拉取「评论通知」增量采集 */
export async function collectCommentNotifications(body?: {
  tab?: string
  limit?: number
  autoAnalyze?: boolean
  credentialId?: string
}): Promise<{
  tab: string
  source: string
  fallbackReason?: string
  notifications: number
  filtered: number
  inserted: number
  analyzed: number
  createdWatches: number
  unread: Record<string, number>
}> {
  const { data } = await apiClient.post<AgentResponse<{
    tab: string
    source: string
    fallbackReason?: string
    notifications: number
    filtered: number
    inserted: number
    analyzed: number
    createdWatches: number
    unread: Record<string, number>
  }>>('/comments/notifications/collect', body ?? {})
  return unwrap(data)
}
/** PUT /comments/watches/{id}/token — 更新监控作品的 xsec_token（票据过期时用） */
export async function updateCommentWatchToken(
  watchId: string,
  xsecToken: string
): Promise<CommentWatch> {
  const { data } = await apiClient.put<AgentResponse<CommentWatch>>(
    `/comments/watches/${watchId}/token`,
    { xsecToken }
  )
  return unwrap(data)
}
/** GET /comments/ops-overview — 运维总览（轮换状态/账号健康/票据预警/最近任务） */
export async function getCommentOpsOverview(): Promise<OpsOverview> {
  const { data } = await apiClient.get<AgentResponse<OpsOverview>>('/comments/ops-overview')
  return unwrap(data)
}
/** POST /comments/token-warnings/notify — 立即推送票据预警到飞书/企微（force=忽略去重） */
export async function notifyTokenWarnings(force = false): Promise<{
  candidates: number
  notified: number
  skipped: number
  items: string[]
  channels: Record<string, unknown>
}> {
  const { data } = await apiClient.post<AgentResponse<{
    candidates: number
    notified: number
    skipped: number
    items: string[]
    channels: Record<string, unknown>
  }>>('/comments/token-warnings/notify', { force })
  return unwrap(data)
}
// ═══════════════════════════════════════════════════════════════
//  评论者关系标签 + 意图语料库
// ═══════════════════════════════════════════════════════════════

/** GET /comments/relations — 关系分布与用户列表 */
export async function getCommentRelations(
  relation?: string,
  limit = 50
): Promise<CommentRelationOverview> {
  const { data } = await apiClient.get<AgentResponse<CommentRelationOverview>>('/comments/relations', {
    params: { relation, limit },
  })
  return unwrap(data)
}

/** PUT /comments/relations/{userKey} — 设置关系标签（关注/好友需人工或外部关系源） */
export async function setCommentUserRelation(
  userKey: string,
  relation: string,
  note?: string
): Promise<void> {
  await apiClient.put(`/comments/relations/${encodeURIComponent(userKey)}`, { relation, note })
}

/** POST /comments/relations/sync-followers — 同步「新增关注」维护粉丝标签 */
export async function syncCommentFollowers(): Promise<number> {
  const { data } = await apiClient.post<AgentResponse<{ marked: number }>>(
    '/comments/relations/sync-followers'
  )
  return unwrap(data).marked ?? 0
}

/** GET /comments/intent-corpus — 语料库列表与概览 */
export async function getIntentCorpus(): Promise<{
  stats: IntentCorpusStats
  entries: IntentCorpusEntry[]
}> {
  const { data } = await apiClient.get<
    AgentResponse<{ stats: IntentCorpusStats; entries: IntentCorpusEntry[] }>
  >('/comments/intent-corpus')
  return unwrap(data)
}

/** POST /comments/intent-corpus — 新增语料 */
export async function addIntentCorpusEntry(intent: string, phrase: string): Promise<IntentCorpusEntry> {
  const { data } = await apiClient.post<AgentResponse<IntentCorpusEntry>>('/comments/intent-corpus', {
    intent,
    phrase,
  })
  return unwrap(data)
}

/** DELETE /comments/intent-corpus/{id} */
export async function deleteIntentCorpusEntry(corpusId: string): Promise<void> {
  await apiClient.delete(`/comments/intent-corpus/${corpusId}`)
}

/** POST /comments/intent-corpus/rebuild — 重建向量缓存 */
export async function rebuildIntentCorpus(): Promise<number> {
  const { data } = await apiClient.post<AgentResponse<{ cacheSize: number }>>(
    '/comments/intent-corpus/rebuild'
  )
  return unwrap(data).cacheSize ?? 0
}

/** POST /comments/intent-corpus/match — 匹配预览（阈值调优） */
export async function previewIntentMatch(
  text: string,
  threshold?: number,
  topK = 5
): Promise<IntentMatchPreview> {
  const { data } = await apiClient.post<AgentResponse<IntentMatchPreview>>(
    '/comments/intent-corpus/match',
    { text, threshold, topK }
  )
  return unwrap(data)
}