/**
 * LocalStorage-based workflow tracker.
 *
 * 记录前端创建的 workflowId 列表，作为 Dashboard 数据来源的补充。
 * 当后端 GET /workflow 列表端点可用时，优先使用后端数据；
 * 当后端端点不可用时，回退到 localStorage 中记录的 workflowId，
 * 逐个调用 GET /workflow/{id}/status 获取状态。
 */

const STORAGE_KEY = 'contentops:workflow-ids'
const MAX_TRACKED = 100

interface TrackedWorkflow {
  workflowId: string
  createdAt: string
  title?: string
}

/** 获取所有已追踪的 workflowId */
export function getTrackedWorkflowIds(): TrackedWorkflow[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
  } catch {
    return []
  }
}

/** 追踪一个新的 workflowId */
export function trackWorkflow(workflowId: string, title?: string): void {
  try {
    const existing = getTrackedWorkflowIds()
    // 避免重复
    if (existing.some((w) => w.workflowId === workflowId)) return
    const updated = [
      { workflowId, createdAt: new Date().toISOString(), title },
      ...existing,
    ].slice(0, MAX_TRACKED)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
  } catch {
    // localStorage 不可用时静默失败
  }
}

/** 移除一个已追踪的 workflowId */
export function untrackWorkflow(workflowId: string): void {
  try {
    const existing = getTrackedWorkflowIds()
    const updated = existing.filter((w) => w.workflowId !== workflowId)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
  } catch {
    // 静默失败
  }
}
