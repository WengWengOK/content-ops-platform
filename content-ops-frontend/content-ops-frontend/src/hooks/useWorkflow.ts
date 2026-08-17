/**
 * React Hooks for workflow API integration.
 * 封装常见的数据获取、轮询、提交模式。
 */
import { useState, useEffect, useCallback, useRef } from 'react'
import {
  getWorkflowStatus,
  getStages,
  approveStage,
  confirmSubStage,
  startDiscussion,
  chatDiscussion,
  finalizeDiscussion,
  listWorkflows,
  fetchLatestAnalysisReport,
} from '@/api/workflow'
import { getTrackedWorkflowIds } from '@/utils/workflowTracker'
import type { TaskContext, StageInfo, DiscussionResponse, DiscussStartRequest, AnalysisReport } from '@/types'

// ═══════════════════════════════════════════
//  useWorkflowStatus — 轮询工作流状态
// ═══════════════════════════════════════════

interface UseWorkflowStatusOptions {
  /** 轮询间隔（毫秒），默认 3000 */
  intervalMs?: number
  /** 状态为终态（COMPLETED/FAILED）时停止轮询 */
  stopOnTerminal?: boolean
}

interface UseWorkflowStatusResult {
  workflow: TaskContext | null
  loading: boolean
  error: string | null
  /** 手动刷新 */
  refresh: () => void
}

export function useWorkflowStatus(
  workflowId: string | null | undefined,
  options: UseWorkflowStatusOptions = {}
): UseWorkflowStatusResult {
  const { intervalMs = 3000, stopOnTerminal = true } = options
  const [workflow, setWorkflow] = useState<TaskContext | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const fetchStatus = useCallback(async () => {
    if (!workflowId) {
      setLoading(false)
      return
    }
    try {
      const ctx = await getWorkflowStatus(workflowId)
      setWorkflow(ctx)
      setError(null)
      if (stopOnTerminal && (ctx.status === 'COMPLETED' || ctx.status === 'FAILED' || ctx.status === 'BUDGET_EXCEEDED')) {
        if (timerRef.current) {
          clearInterval(timerRef.current)
          timerRef.current = null
        }
      }
    } catch (err: any) {
      setError(err?.message || '获取工作流状态失败')
    } finally {
      setLoading(false)
    }
  }, [workflowId, stopOnTerminal])

  const refresh = useCallback(() => {
    setLoading(true)
    fetchStatus()
  }, [fetchStatus])

  useEffect(() => {
    fetchStatus()

    if (workflowId && intervalMs > 0) {
      timerRef.current = setInterval(fetchStatus, intervalMs)
    }

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
  }, [workflowId, intervalMs, fetchStatus])

  return { workflow, loading, error, refresh }
}

// ═══════════════════════════════════════════
//  useStages — 获取所有流水线阶段
// ═══════════════════════════════════════════

interface UseStagesResult {
  stages: StageInfo[]
  loading: boolean
  error: string | null
}

export function useStages(): UseStagesResult {
  const [stages, setStages] = useState<StageInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getStages()
      .then((data) => {
        setStages(data)
        setError(null)
      })
      .catch((err) => setError(err?.message || '获取阶段列表失败'))
      .finally(() => setLoading(false))
  }, [])

  return { stages, loading, error }
}

// ═══════════════════════════════════════════
//  useApproveStage — 审批推进
// ═══════════════════════════════════════════

interface UseApproveStageResult {
  approving: boolean
  error: string | null
  approve: (workflowId: string, feedback?: Record<string, unknown>) => Promise<boolean>
}

export function useApproveStage(): UseApproveStageResult {
  const [approving, setApproving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const approve = useCallback(async (
    workflowId: string,
    feedback?: Record<string, unknown>
  ): Promise<boolean> => {
    setApproving(true)
    setError(null)
    try {
      await approveStage(workflowId, feedback)
      return true
    } catch (err: any) {
      setError(err?.message || '审批失败')
      return false
    } finally {
      setApproving(false)
    }
  }, [])

  return { approving, error, approve }
}

// ═══════════════════════════════════════════
//  useConfirmSubStage — 确认子阶段
// ═══════════════════════════════════════════

interface UseConfirmSubStageResult {
  confirming: boolean
  error: string | null
  confirm: (workflowId: string, body?: Record<string, unknown>) => Promise<boolean>
}

export function useConfirmSubStage(): UseConfirmSubStageResult {
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const confirm = useCallback(async (
    workflowId: string,
    body?: Record<string, unknown>
  ): Promise<boolean> => {
    setConfirming(true)
    setError(null)
    try {
      await confirmSubStage(workflowId, body)
      return true
    } catch (err: any) {
      setError(err?.message || '确认子阶段失败')
      return false
    } finally {
      setConfirming(false)
    }
  }, [])

  return { confirming, error, confirm }
}

// ═══════════════════════════════════════════
//  useDiscussion — 讨论模式
// ═══════════════════════════════════════════

interface UseDiscussionResult {
  sessionId: string | null
  messages: Array<{ role: 'user' | 'assistant'; content: string }>
  loading: boolean
  error: string | null
  canFinalize: boolean
  start: (req: DiscussStartRequest) => Promise<void>
  send: (message: string) => Promise<void>
  finalize: (startPipeline?: boolean) => Promise<string | null>
}

export function useDiscussion(): UseDiscussionResult {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<Array<{ role: 'user' | 'assistant'; content: string }>>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [canFinalize, setCanFinalize] = useState(false)

  const start = useCallback(async (req: DiscussStartRequest): Promise<void> => {
    setLoading(true)
    setError(null)
    try {
      const resp: DiscussionResponse = await startDiscussion(req)
      setSessionId(resp.sessionId)
      setMessages([
        { role: 'user', content: req.fuzzyIdea },
        { role: 'assistant', content: resp.message },
      ])
      setCanFinalize(resp.canFinalize)
    } catch (err: any) {
      setError(err?.message || '启动讨论失败')
    } finally {
      setLoading(false)
    }
  }, [])

  const send = useCallback(async (message: string): Promise<void> => {
    if (!sessionId) return
    setLoading(true)
    setError(null)
    try {
      setMessages((prev) => [...prev, { role: 'user', content: message }])
      const resp = await chatDiscussion(sessionId, { message })
      setMessages((prev) => [...prev, { role: 'assistant', content: resp.message }])
      setCanFinalize(resp.canFinalize)
    } catch (err: any) {
      setError(err?.message || '发送消息失败')
    } finally {
      setLoading(false)
    }
  }, [sessionId])

  const finalize = useCallback(async (startPipeline = true): Promise<string | null> => {
    if (!sessionId) return null
    setLoading(true)
    setError(null)
    try {
      const result = await finalizeDiscussion(sessionId, startPipeline)
      return result.workflowId ?? null
    } catch (err: any) {
      setError(err?.message || '结束讨论失败')
      return null
    } finally {
      setLoading(false)
    }
  }, [sessionId])

  return { sessionId, messages, loading, error, canFinalize, start, send, finalize }
}

// ═══════════════════════════════════════════
//  useDashboardData — 仪表盘数据聚合
// ═══════════════════════════════════════════

export interface DashboardStats {
  running: number
  awaitingReview: number
  completed: number
  total: number
}

export interface DashboardWorkflow {
  workflowId: string
  title: string
  status: string
  statusLabel: string
  stageCode: string
  stageLabel: string
  progress: number
  createdAt: string
  updatedAt: string
}

export interface DashboardActivity {
  title: string
  desc: string
  time: string
  color: string
  pulse: boolean
}

interface UseDashboardDataResult {
  workflows: DashboardWorkflow[]
  stats: DashboardStats
  activities: DashboardActivity[]
  loading: boolean
  error: string | null
  refresh: () => void
}

export function useDashboardData(): UseDashboardDataResult {
  const [workflows, setWorkflows] = useState<TaskContext[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), [])

  useEffect(() => {
    let cancelled = false

    async function fetchData() {
      setLoading(true)
      setError(null)
      try {
        // 优先尝试后端列表端点
        let result: TaskContext[] = []
        try {
          result = await listWorkflows()
        } catch {
          // 后端端点不可用时，回退到 localStorage 追踪
          const tracked = getTrackedWorkflowIds()
          if (tracked.length > 0) {
            const statuses = await Promise.allSettled(
              tracked.map((t) => getWorkflowStatus(t.workflowId))
            )
            result = statuses
              .filter(
                (s): s is PromiseFulfilledResult<TaskContext> =>
                  s.status === 'fulfilled' && s.value !== null
              )
              .map((s) => s.value)
          }
        }
        if (!cancelled) {
          setWorkflows(result)
        }
      } catch (err: any) {
        if (!cancelled) {
          setError(err?.message || '获取工作流列表失败')
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    fetchData()
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  // 将 TaskContext[] 转换为 DashboardWorkflow[]
  const dashboardWorkflows: DashboardWorkflow[] = workflows.map((ctx) => {
    const stageOrder: Record<string, number> = {
      'topic-planning': 1,
      'content-creation': 2,
      'image-design': 3,
      'publishing': 4,
    }
    const currentOrder = stageOrder[ctx.currentStage] || 0
    const progress = ctx.status === 'COMPLETED' ? 100 : Math.round((currentOrder / 4) * 100)

    const statusLabelMap: Record<string, string> = {
      PENDING: '等待中',
      IN_PROGRESS: '运行中',
      AWAITING_HUMAN: '待审核',
      AWAITING_ASYNC: '异步等待',
      COMPLETED: '已完成',
      FAILED: '失败',
      BUDGET_EXCEEDED: '预算用尽',
      SKIPPED: '已跳过',
    }

    const stageLabelMap: Record<string, string> = {
      'topic-planning': '选题策划',
      'content-creation': '内容创作',
      'image-design': '配图设计',
      'publishing': '排版发布',
      'data-analysis': '数据分析',
      'optimization': '优化迭代',
    }

    return {
      workflowId: ctx.workflowId,
      title: (ctx.inputs?.additionalContext as string) || ctx.accountProfile?.accountName || '未命名工作流',
      status: ctx.status,
      statusLabel: statusLabelMap[ctx.status] || ctx.status,
      stageCode: ctx.currentStage,
      stageLabel: stageLabelMap[ctx.currentStage] || ctx.currentStage,
      progress,
      createdAt: ctx.createdAt,
      updatedAt: ctx.updatedAt,
    }
  })

  // 计算统计
  const stats: DashboardStats = {
    running: workflows.filter(
      (w) => w.status === 'IN_PROGRESS' || w.status === 'PENDING'
    ).length,
    awaitingReview: workflows.filter((w) => w.status === 'AWAITING_HUMAN').length,
    completed: workflows.filter((w) => w.status === 'COMPLETED').length,
    total: workflows.length,
  }

  // 生成最近动态（从 workflows 推导）
  const activities: DashboardActivity[] = workflows.slice(0, 6).map((ctx) => {
    const statusLabelMap: Record<string, string> = {
      PENDING: '等待中',
      IN_PROGRESS: '运行中',
      AWAITING_HUMAN: '待审核',
      AWAITING_ASYNC: '异步等待',
      COMPLETED: '已完成',
      FAILED: '失败',
      BUDGET_EXCEEDED: '预算用尽',
      SKIPPED: '已跳过',
    }
    const stageLabelMap: Record<string, string> = {
      'topic-planning': '选题策划',
      'content-creation': '内容创作',
      'image-design': '配图设计',
      'publishing': '排版发布',
      'data-analysis': '数据分析',
      'optimization': '优化迭代',
    }
    const stageLabel = stageLabelMap[ctx.currentStage] || ctx.currentStage
    const statusLabel = statusLabelMap[ctx.status] || ctx.status
    const title = (ctx.inputs?.additionalContext as string) || ctx.accountProfile?.accountName || '未命名工作流'
    const colorMap: Record<string, string> = {
      IN_PROGRESS: '#165DFF',
      PENDING: '#165DFF',
      AWAITING_HUMAN: '#FF7D00',
      COMPLETED: '#00B42A',
      FAILED: '#F53F3F',
      BUDGET_EXCEEDED: '#F53F3F',
    }
    const titleMap: Record<string, string> = {
      IN_PROGRESS: `${stageLabel}进行中`,
      PENDING: '工作流等待启动',
      AWAITING_HUMAN: `${stageLabel}待审核`,
      COMPLETED: `${stageLabel}已完成`,
      FAILED: `${stageLabel}失败`,
    }

    return {
      title: titleMap[ctx.status] || statusLabel,
      desc: `「${title}」— ${stageLabel}`,
      time: ctx.updatedAt || ctx.createdAt,
      color: colorMap[ctx.status] || '#C9CDD4',
      pulse: ctx.status === 'IN_PROGRESS',
    }
  })

  return { workflows: dashboardWorkflows, stats, activities, loading, error, refresh }
}

// ═══════════════════════════════════════════
//  useAnalysisReport — 获取最新分析报告
// ═══════════════════════════════════════════

interface UseAnalysisReportResult {
  report: AnalysisReport | null
  loading: boolean
  error: string | null
  refresh: () => void
}

export function useAnalysisReport(): UseAnalysisReportResult {
  const [report, setReport] = useState<AnalysisReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), [])

  useEffect(() => {
    let cancelled = false

    async function fetchReport() {
      setLoading(true)
      setError(null)
      try {
        const result = await fetchLatestAnalysisReport()
        if (!cancelled) {
          setReport(result)
        }
      } catch (err: any) {
        if (!cancelled) {
          setError(err?.message || '获取分析报告失败')
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    fetchReport()
    return () => { cancelled = true }
  }, [refreshKey])

  return { report, loading, error, refresh }
}
