/**
 * 评论区 AI 助手 API —— 采集/分析/对话/审核/统计。
 */
import { apiClient } from './client'
import type { AgentResponse, CommentStats, PlatformComment } from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** POST /comments/collect — 采集评论（MVP 模拟小红书数据源） */
export async function collectComments(
  workId: string
): Promise<{ collected: number; inserted: number; comments: PlatformComment[] }> {
  const { data } = await apiClient.post<AgentResponse<{
    collected: number
    inserted: number
    comments: PlatformComment[]
  }>>('/comments/collect', { workId })
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

/** POST /comments/{id}/send — 发送回复（MVP 模拟） */
export async function sendCommentReply(commentId: string): Promise<PlatformComment> {
  const { data } = await apiClient.post<AgentResponse<PlatformComment>>(
    `/comments/${commentId}/send`
  )
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
