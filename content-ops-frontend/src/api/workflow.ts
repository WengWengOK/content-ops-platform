/**
 * Workflow API Service — 封装 Orchestrator (端口 8080) 的全部 REST 端点调用。
 *
 * 所有方法返回解包后的 data（AgentResponse.data），失败时抛出 Error。
 * 对应后端: WorkflowController @RequestMapping("/api/v1/workflow")
 */
import { apiClient } from './client'
import type {
  AgentResponse,
  TaskContext,
  StageInfo,
  DiscussionResponse,
  DiscussionSession,
  TopicPlanResult,
  StartWorkflowRequest,
  DiscussStartRequest,
  DiscussChatRequest,
  AnalysisReport,
} from '@/types'

/** 解包 AgentResponse：成功返回 data，失败抛错 */
function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

// ═══════════════════════════════════════════
//  Workflow 端点
// ═══════════════════════════════════════════

/** POST /workflow/start — 启动工作流 */
export async function startWorkflow(req: StartWorkflowRequest): Promise<{
  workflowId: string
  currentStage: string
  message: string
}> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>('/workflow/start', req)
  const d = unwrap(data)
  return {
    workflowId: String(d.workflowId ?? ''),
    currentStage: String(d.currentStage ?? ''),
    message: String(d.message ?? ''),
  }
}

/** GET /workflow/{workflowId}/status — 查询工作流状态 */
export async function getWorkflowStatus(workflowId: string): Promise<TaskContext> {
  const { data } = await apiClient.get<AgentResponse<TaskContext>>(`/workflow/${workflowId}/status`)
  return unwrap(data)
}

/** GET /workflow — 获取所有工作流列表（仪表盘用） */
export async function listWorkflows(): Promise<TaskContext[]> {
  const { data } = await apiClient.get<AgentResponse<TaskContext[]>>('/workflow')
  return unwrap(data)
}

/**
 * 从已完成工作流中提取最新的分析报告。
 * 遍历所有工作流，找到最近的 COMPLETED 且包含 data-analysis 产物的工作流，
 * 从 accumulatedArtifacts["data-analysis"] 中提取 AnalysisReport。
 */
export async function fetchLatestAnalysisReport(): Promise<AnalysisReport | null> {
  const workflows = await listWorkflows()
  for (const wf of workflows) {
    if (wf.status !== 'COMPLETED') continue
    const artifacts = wf.accumulatedArtifacts as Record<string, any>
    const analysisArtifact = artifacts?.['data-analysis']
    if (analysisArtifact) {
      // 分析报告可能直接是对象，也可能嵌套在 report 字段中
      const report = analysisArtifact.report || analysisArtifact.analysisReport || analysisArtifact
      if (report && (report.insights || report.recommendations || report.keyMetrics)) {
        return report as AnalysisReport
      }
    }
  }
  return null
}

/** POST /workflow/{workflowId}/approve — 审批推进 */
export async function approveStage(
  workflowId: string,
  feedback?: Record<string, unknown>
): Promise<{ message: string }> {
  const params = feedback ? { params: feedback } : undefined
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/approve`,
    null,
    params
  )
  const d = unwrap(data)
  return { message: String(d.message ?? '') }
}

/** POST /workflow/{workflowId}/confirm-substage — 确认子阶段 */
export async function confirmSubStage(
  workflowId: string,
  body?: Record<string, unknown>
): Promise<{ message: string }> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/confirm-substage`,
    body ?? {}
  )
  const d = unwrap(data)
  return { message: String(d.message ?? '') }
}

/** GET /workflow/stages — 获取所有流水线阶段 */
export async function getStages(): Promise<StageInfo[]> {
  const { data } = await apiClient.get<AgentResponse<StageInfo[]>>('/workflow/stages')
  return unwrap(data)
}

// ═══════════════════════════════════════════
//  Discussion 端点
// ═══════════════════════════════════════════

/** POST /workflow/discuss/start — 启动讨论会话 */
export async function startDiscussion(req: DiscussStartRequest): Promise<DiscussionResponse> {
  const { data } = await apiClient.post<AgentResponse<DiscussionResponse>>('/workflow/discuss/start', req)
  return unwrap(data)
}

/** POST /workflow/discuss/{sessionId}/chat — 讨论对话 */
export async function chatDiscussion(
  sessionId: string,
  req: DiscussChatRequest
): Promise<DiscussionResponse> {
  const { data } = await apiClient.post<AgentResponse<DiscussionResponse>>(
    `/workflow/discuss/${sessionId}/chat`,
    req
  )
  return unwrap(data)
}

/** POST /workflow/discuss/{sessionId}/finalize — 结束讨论并启动流水线 */
export async function finalizeDiscussion(
  sessionId: string,
  startPipeline = true
): Promise<{
  sessionId: string
  topicPlan?: TopicPlanResult
  workflowId?: string
  currentStage?: string
  message: string
}> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/discuss/${sessionId}/finalize`,
    null,
    { params: { startPipeline } }
  )
  return unwrap(data) as any
}

/** GET /workflow/discuss/{sessionId} — 获取讨论会话状态 */
export async function getDiscussionSession(sessionId: string): Promise<DiscussionSession> {
  const { data } = await apiClient.get<AgentResponse<DiscussionSession>>(`/workflow/discuss/${sessionId}`)
  return unwrap(data)
}

/** DELETE /workflow/discuss/{sessionId} — 清除讨论会话 */
export async function clearDiscussion(sessionId: string): Promise<void> {
  await apiClient.delete<AgentResponse<void>>(`/workflow/discuss/${sessionId}`)
}
