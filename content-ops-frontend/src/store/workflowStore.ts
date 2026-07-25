import { create } from 'zustand'
import type { TaskContext, StageInfo, AccountProfile } from '@/types'
import { orchestratorApi } from '@/api/orchestrator'

interface WorkflowState {
  // Workflow state
  currentWorkflowId: string | null
  taskContext: TaskContext | null
  stages: StageInfo[]
  loading: boolean
  error: string | null

  // Account profile form
  accountProfile: AccountProfile
  requireHumanReview: boolean

  // Actions
  setAccountProfile: (profile: Partial<AccountProfile>) => void
  setRequireHumanReview: (val: boolean) => void
  startWorkflow: () => Promise<void>
  fetchWorkflowStatus: (workflowId: string) => Promise<void>
  approveStage: (feedback?: Record<string, unknown>) => Promise<void>
  confirmSubStage: (body?: Record<string, unknown>) => Promise<void>
  fetchStages: () => Promise<void>
  reset: () => void
}

const defaultProfile: AccountProfile = {
  accountId: '',
  accountName: '',
  niche: '个人成长',
  targetAudience: '25-35岁职场人士',
  tone: '专业严谨',
  platforms: ['公众号', '小红书'],
  personalExperience: '',
}

export const useWorkflowStore = create<WorkflowState>((set, get) => ({
  currentWorkflowId: null,
  taskContext: null,
  stages: [],
  loading: false,
  error: null,
  accountProfile: defaultProfile,
  requireHumanReview: true,

  setAccountProfile: (profile) =>
    set((state) => ({ accountProfile: { ...state.accountProfile, ...profile } })),

  setRequireHumanReview: (val) => set({ requireHumanReview: val }),

  startWorkflow: async () => {
    const { accountProfile, requireHumanReview } = get()
    set({ loading: true, error: null })
    try {
      const res = await orchestratorApi.startWorkflow({
        accountProfile,
        inputs: {},
        requireHumanReview,
      })
      const workflowId = (res.data as Record<string, unknown>)?.workflowId as string
      set({ currentWorkflowId: workflowId, loading: false })
      if (workflowId) {
        await get().fetchWorkflowStatus(workflowId)
      }
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '启动工作流失败', loading: false })
    }
  },

  fetchWorkflowStatus: async (workflowId) => {
    set({ loading: true, error: null })
    try {
      const res = await orchestratorApi.getWorkflowStatus(workflowId)
      set({ taskContext: res.data, loading: false })
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '获取工作流状态失败', loading: false })
    }
  },

  approveStage: async (feedback) => {
    const { currentWorkflowId } = get()
    if (!currentWorkflowId) return
    set({ loading: true, error: null })
    try {
      await orchestratorApi.approveStage(currentWorkflowId, feedback)
      await get().fetchWorkflowStatus(currentWorkflowId)
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '审批失败', loading: false })
    }
  },

  confirmSubStage: async (body) => {
    const { currentWorkflowId } = get()
    if (!currentWorkflowId) return
    set({ loading: true, error: null })
    try {
      await orchestratorApi.confirmSubStage(currentWorkflowId, body)
      await get().fetchWorkflowStatus(currentWorkflowId)
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '确认子阶段失败', loading: false })
    }
  },

  fetchStages: async () => {
    try {
      const res = await orchestratorApi.getStages()
      set({ stages: res.data })
    } catch (err) {
      console.error('Failed to fetch stages:', err)
    }
  },

  reset: () =>
    set({
      currentWorkflowId: null,
      taskContext: null,
      loading: false,
      error: null,
    }),
}))
