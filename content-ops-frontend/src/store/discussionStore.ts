import { create } from 'zustand'
import type { DiscussionResponse, DiscussionSession, AccountProfile } from '@/types'
import { orchestratorApi } from '@/api/orchestrator'

interface DiscussionState {
  sessionId: string | null
  session: DiscussionSession | null
  lastResponse: DiscussionResponse | null
  loading: boolean
  error: string | null
  fuzzyIdea: string
  accountProfile: AccountProfile

  setFuzzyIdea: (idea: string) => void
  setAccountProfile: (profile: Partial<AccountProfile>) => void
  startDiscussion: () => Promise<void>
  chat: (message: string) => Promise<void>
  finalize: (startPipeline?: boolean) => Promise<string | null>
  fetchSession: () => Promise<void>
  clearSession: () => Promise<void>
  reset: () => void
}

const defaultProfile: AccountProfile = {
  accountId: '',
  accountName: '',
  niche: '个人成长',
  targetAudience: '25-35岁职场人士',
  tone: '专业严谨',
  platforms: ['公众号'],
  personalExperience: '',
}

export const useDiscussionStore = create<DiscussionState>((set, get) => ({
  sessionId: null,
  session: null,
  lastResponse: null,
  loading: false,
  error: null,
  fuzzyIdea: '',
  accountProfile: defaultProfile,

  setFuzzyIdea: (idea) => set({ fuzzyIdea: idea }),

  setAccountProfile: (profile) =>
    set((state) => ({ accountProfile: { ...state.accountProfile, ...profile } })),

  startDiscussion: async () => {
    const { fuzzyIdea, accountProfile } = get()
    if (!fuzzyIdea.trim()) {
      set({ error: '请输入你的模糊想法' })
      return
    }
    set({ loading: true, error: null })
    try {
      const res = await orchestratorApi.startDiscussion({ fuzzyIdea, accountProfile })
      set({
        sessionId: res.data.sessionId,
        lastResponse: res.data,
        loading: false,
      })
      await get().fetchSession()
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '启动讨论失败', loading: false })
    }
  },

  chat: async (message) => {
    const { sessionId } = get()
    if (!sessionId) return
    set({ loading: true, error: null })
    try {
      const res = await orchestratorApi.chatDiscussion(sessionId, { message })
      set({ lastResponse: res.data, loading: false })
      await get().fetchSession()
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '发送消息失败', loading: false })
    }
  },

  finalize: async (startPipeline = true) => {
    const { sessionId } = get()
    if (!sessionId) return null
    set({ loading: true, error: null })
    try {
      const res = await orchestratorApi.finalizeDiscussion(sessionId, startPipeline)
      const workflowId = (res.data as Record<string, unknown>)?.workflowId as string
      set({ loading: false })
      return workflowId
    } catch (err) {
      set({ error: err instanceof Error ? err.message : '结束讨论失败', loading: false })
      return null
    }
  },

  fetchSession: async () => {
    const { sessionId } = get()
    if (!sessionId) return
    try {
      const res = await orchestratorApi.getDiscussionSession(sessionId)
      set({ session: res.data })
    } catch (err) {
      console.error('Failed to fetch session:', err)
    }
  },

  clearSession: async () => {
    const { sessionId } = get()
    if (!sessionId) return
    try {
      await orchestratorApi.clearDiscussion(sessionId)
      set({ sessionId: null, session: null, lastResponse: null })
    } catch (err) {
      console.error('Failed to clear session:', err)
    }
  },

  reset: () =>
    set({
      sessionId: null,
      session: null,
      lastResponse: null,
      loading: false,
      error: null,
      fuzzyIdea: '',
    }),
}))
