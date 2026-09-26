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
  CommentWatch,
  PlatformComment,
} from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** POST /comments/collect — 采集评论（后端按配置走真实接口或模拟数据源） */
export async function collectComments(
  workId: string,
  platform = 'xiaohongshu'
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
  }>>('/comments/collect', { workId, platform })
  return unwrap(data)
}

/** GET /comments — 评论列表（平台/作品/意图/情感过滤） */
export async function listComments(params: {
  platform?: string
  workId?: string
  intent?: string
  sentiment?: string
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