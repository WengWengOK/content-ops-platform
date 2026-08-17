/**
 * 作品合集 API Service —— 封装 /api/v1/collections 全部端点。
 * 合集按类型区分（干货知识 / 情感故事 / 产品种草 ...），作品可在创建时或生成后归入合集。
 */
import { apiClient } from './client'
import type { AgentResponse, WorkCollection } from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** GET /collections — 列出我的作品合集 */
export async function listCollections(): Promise<WorkCollection[]> {
  const { data } = await apiClient.get<AgentResponse<WorkCollection[]>>('/collections')
  return unwrap(data)
}

/** GET /collections/{id} — 合集详情（含作品列表） */
export async function getCollection(collectionId: string): Promise<WorkCollection> {
  const { data } = await apiClient.get<AgentResponse<WorkCollection>>(`/collections/${collectionId}`)
  return unwrap(data)
}

/** POST /collections — 创建合集（按类型区分） */
export async function createCollection(req: {
  name: string
  type: string
  description?: string
}): Promise<WorkCollection> {
  const { data } = await apiClient.post<AgentResponse<WorkCollection>>('/collections', req)
  return unwrap(data)
}

/** PUT /collections/{id} — 更新合集 */
export async function updateCollection(
  collectionId: string,
  req: { name?: string; type?: string; description?: string }
): Promise<WorkCollection> {
  const { data } = await apiClient.put<AgentResponse<WorkCollection>>(
    `/collections/${collectionId}`,
    req
  )
  return unwrap(data)
}

/** DELETE /collections/{id} — 删除合集 */
export async function deleteCollection(collectionId: string): Promise<void> {
  await apiClient.delete(`/collections/${collectionId}`)
}

/** POST /collections/{id}/works — 把作品加入合集 */
export async function addWorkToCollection(collectionId: string, workflowId: string): Promise<void> {
  await apiClient.post(`/collections/${collectionId}/works`, { workflowId })
}

/** DELETE /collections/{id}/works/{workflowId} — 把作品移出合集 */
export async function removeWorkFromCollection(
  collectionId: string,
  workflowId: string
): Promise<void> {
  await apiClient.delete(`/collections/${collectionId}/works/${workflowId}`)
}

/** GET /collections/works/{workflowId} — 查询某作品所在的合集列表 */
export async function listCollectionsByWorkflow(workflowId: string): Promise<WorkCollection[]> {
  const { data } = await apiClient.get<AgentResponse<WorkCollection[]>>(
    `/collections/works/${workflowId}`
  )
  return unwrap(data)
}
