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
  SelectPlatformsRequest,
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

/**
 * GET /workflow — 获取工作流列表（分页）。
 *
 * 后端返回分页结构：{ content: TaskContext[], page, size, total, totalPages }，
 * 这里统一解包为 TaskContext[] 供 Dashboard / DataCenter 使用。
 */
interface WorkflowListResponse {
  content: TaskContext[]
  page: number
  size: number
  total: number
  totalPages: number
}

export async function listWorkflows(): Promise<TaskContext[]> {
  const { data } = await apiClient.get<AgentResponse<WorkflowListResponse | TaskContext[]>>(
    '/workflow',
    { params: { page: 0, size: 100 } }
  )
  const d = unwrap(data)
  // 防御性兼容：后端若是裸数组也直接返回
  if (Array.isArray(d)) return d
  return d?.content ?? []
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

/** POST /workflow/{workflowId}/select-platforms — 选题确认后选择发布平台 */
export async function selectPlatforms(
  workflowId: string,
  req: SelectPlatformsRequest
): Promise<{ message: string; platforms: string[] }> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/select-platforms`,
    req
  )
  const d = unwrap(data)
  return {
    message: String(d.message ?? ''),
    platforms: Array.isArray(d.platforms) ? (d.platforms as string[]) : [],
  }
}

/** POST /workflow/{workflowId}/cover — 上传封面替换 AI 生成封面 */
export async function uploadWorkflowCover(
  workflowId: string,
  file: File
): Promise<{ coverImageUrl: string; replaced: boolean }> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/cover`,
    form
  )
  const d = unwrap(data)
  return {
    coverImageUrl: String(d.coverImageUrl ?? ''),
    replaced: Boolean(d.replaced),
  }
}

/** POST /workflow/{workflowId}/materials — 上传创作素材（AI 自动分析选题/创作） */
export async function uploadWorkflowMaterial(
  workflowId: string,
  file: File
): Promise<{ name: string; textExtracted: boolean }> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/materials`,
    form
  )
  const d = unwrap(data)
  return {
    name: String(d.name ?? ''),
    textExtracted: Boolean(d.textExtracted),
  }
}

/** PUT /workflow/{workflowId}/content — 保存用户确定性修改的标题/正文 */
export async function updateWorkflowContent(
  workflowId: string,
  body: { title?: string; content?: string }
): Promise<{ title: string; content: string; applied: boolean }> {
  const { data } = await apiClient.put<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/content`,
    body
  )
  const d = unwrap(data)
  return {
    title: String(d.title ?? ''),
    content: String(d.content ?? ''),
    applied: Boolean(d.applied),
  }
}

/** GET /workflow/stages — 获取所有流水线阶段 */
export async function getStages(): Promise<StageInfo[]> {
  const { data } = await apiClient.get<AgentResponse<StageInfo[]>>('/workflow/stages')
  return unwrap(data)
}

/** GET /workflow/{workflowId}/download — 下载作品（ZIP 打包） */
export async function downloadWorkflow(workflowId: string): Promise<Blob> {
  const { data } = await apiClient.get<Blob>(`/workflow/${workflowId}/download`, {
    responseType: 'blob',
  })
  return data
}

/** POST /workflow/{workflowId}/analyze — 独立运行数据分析服务 */
export async function runStandaloneAnalysis(workflowId: string): Promise<{ message: string }> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/analyze`
  )
  const d = unwrap(data)
  return { message: String(d.message ?? '') }
}

/** POST /workflow/{workflowId}/optimize — 独立运行优化迭代服务 */
export async function runStandaloneOptimize(workflowId: string): Promise<{ message: string }> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/optimize`
  )
  const d = unwrap(data)
  return { message: String(d.message ?? '') }
}

/** GET /workflow/{workflowId}/discussions — 查看作品的聊天记录 */
export async function listWorkflowDiscussions(workflowId: string): Promise<DiscussionSession[]> {
  const { data } = await apiClient.get<AgentResponse<DiscussionSession[]>>(
    `/workflow/${workflowId}/discussions`
  )
  return unwrap(data)
}

/** POST /workflow/{workflowId}/discuss/start — 针对作品开启聊天续改会话 */
export async function startWorkflowDiscussion(
  workflowId: string,
  fuzzyIdea?: string
): Promise<DiscussionResponse> {
  const { data } = await apiClient.post<AgentResponse<DiscussionResponse>>(
    `/workflow/${workflowId}/discuss/start`,
    fuzzyIdea ? { fuzzyIdea } : {}
  )
  return unwrap(data)
}

/** POST /workflow/{workflowId}/discuss/{sessionId}/apply — 将聊天修改意见应用到作品 */
export async function applyDiscussionModification(
  workflowId: string,
  sessionId: string
): Promise<{ applied: boolean; title: string; content: string }> {
  const { data } = await apiClient.post<AgentResponse<Record<string, unknown>>>(
    `/workflow/${workflowId}/discuss/${sessionId}/apply`
  )
  const d = unwrap(data)
  return {
    applied: Boolean(d.applied),
    title: String(d.title ?? ''),
    content: String(d.content ?? ''),
  }
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
