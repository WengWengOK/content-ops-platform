/**
 * LLM 可观测性 API —— trace 查询 + token/成本/延迟统计。
 */
import { apiClient } from './client'
import type { AgentResponse, LlmStats, LlmTrace } from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** GET /observability/llm/stats — 统计大盘 */
export async function llmStats(hours = 24): Promise<LlmStats> {
  const { data } = await apiClient.get<AgentResponse<LlmStats>>('/observability/llm/stats', {
    params: { hours },
  })
  return unwrap(data)
}

/** GET /observability/llm/traces — 最近调用追踪 */
export async function llmTraces(
  stage?: string,
  agent?: string,
  workflowId?: string,
  limit = 50
): Promise<LlmTrace[]> {
  const { data } = await apiClient.get<AgentResponse<{ total: number; traces: LlmTrace[] }>>(
    '/observability/llm/traces',
    { params: { stage, agent, workflowId, limit } }
  )
  return unwrap(data).traces ?? []
}
