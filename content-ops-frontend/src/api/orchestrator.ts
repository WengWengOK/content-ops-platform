import { apiClient } from './client'
import type {
  AgentResponse,
  TaskContext,
  StageInfo,
  StartWorkflowRequest,
  DiscussStartRequest,
  DiscussChatRequest,
  DiscussionResponse,
  DiscussionSession,
} from '@/types'

const BASE = '/workflow'

export const orchestratorApi = {
  startWorkflow: (req: StartWorkflowRequest) =>
    apiClient.post<AgentResponse<Record<string, unknown>>>(`${BASE}/start`, req).then(r => r.data),

  getWorkflowStatus: (workflowId: string) =>
    apiClient.get<AgentResponse<TaskContext>>(`${BASE}/${workflowId}/status`).then(r => r.data),

  approveStage: (workflowId: string, feedback?: Record<string, unknown>) =>
    apiClient.post<AgentResponse<Record<string, unknown>>>(`${BASE}/${workflowId}/approve`, null, {
      params: feedback,
    }).then(r => r.data),

  confirmSubStage: (workflowId: string, body?: Record<string, unknown>) =>
    apiClient.post<AgentResponse<Record<string, unknown>>>(`${BASE}/${workflowId}/confirm-substage`, body).then(r => r.data),

  getStages: () =>
    apiClient.get<AgentResponse<StageInfo[]>>(`${BASE}/stages`).then(r => r.data),

  startDiscussion: (req: DiscussStartRequest) =>
    apiClient.post<AgentResponse<DiscussionResponse>>(`${BASE}/discuss/start`, req).then(r => r.data),

  chatDiscussion: (sessionId: string, req: DiscussChatRequest) =>
    apiClient.post<AgentResponse<DiscussionResponse>>(`${BASE}/discuss/${sessionId}/chat`, req).then(r => r.data),

  finalizeDiscussion: (sessionId: string, startPipeline = true) =>
    apiClient.post<AgentResponse<Record<string, unknown>>>(`${BASE}/discuss/${sessionId}/finalize`, null, {
      params: { startPipeline },
    }).then(r => r.data),

  getDiscussionSession: (sessionId: string) =>
    apiClient.get<AgentResponse<DiscussionSession>>(`${BASE}/discuss/${sessionId}`).then(r => r.data),

  clearDiscussion: (sessionId: string) =>
    apiClient.delete<AgentResponse<void>>(`${BASE}/discuss/${sessionId}`).then(r => r.data),
}
