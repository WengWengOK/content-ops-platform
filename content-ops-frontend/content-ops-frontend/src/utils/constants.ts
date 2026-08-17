import type { StageCode, WorkflowStatus, DiscussionPhase } from '@/types'

export const STAGE_META: Record<StageCode, { name: string; icon: string; color: string; description: string }> = {
  'topic-planning': { name: '选题策划', icon: 'Lightbulb', color: '#f59e0b', description: '分析热点趋势，生成选题候选' },
  'content-creation': { name: '内容创作', icon: 'PenLine', color: '#3b82f6', description: '生成大纲并撰写内容初稿' },
  'image-design': { name: '配图设计', icon: 'ImageIcon', color: '#8b5cf6', description: '生成风格方向并创建配图' },
  'publishing': { name: '排版发布', icon: 'Send', color: '#10b981', description: '多平台排版并发布内容' },
  'data-analysis': { name: '数据分析', icon: 'BarChart3', color: '#06b6d4', description: '分析运营数据并生成洞察' },
  'optimization': { name: '优化迭代', icon: 'RefreshCw', color: '#ec4899', description: '基于数据优化策略' },
}

export const STAGE_ORDER: StageCode[] = [
  'topic-planning', 'content-creation', 'image-design', 'publishing'
]

/** 独立服务（不进主流水线，按需对已完成作品调用） */
export const STANDALONE_SERVICES: StageCode[] = ['data-analysis', 'optimization']

export const STATUS_META: Record<WorkflowStatus, { label: string; color: string; bgColor: string }> = {
  PENDING: { label: '等待中', color: '#6b7280', bgColor: '#f3f4f6' },
  IN_PROGRESS: { label: '运行中', color: '#3b82f6', bgColor: '#dbeafe' },
  AWAITING_HUMAN: { label: '待审核', color: '#f59e0b', bgColor: '#fef3c7' },
  AWAITING_ASYNC: { label: '异步等待', color: '#f59e0b', bgColor: '#fef3c7' },
    COMPLETED: { label: '已完成', color: '#10b981', bgColor: '#d1fae5' },
    FAILED: { label: '失败', color: '#ef4444', bgColor: '#fee2e2' },
    BUDGET_EXCEEDED: { label: '预算用尽', color: '#ef4444', bgColor: '#fee2e2' },
    SKIPPED: { label: '已跳过', color: '#9ca3af', bgColor: '#f3f4f6' },
}

export const DISCUSSION_PHASE_META: Record<DiscussionPhase, { label: string; color: string }> = {
  IDEATION: { label: '构思中', color: '#f59e0b' },
  CLARIFICATION: { label: '澄清需求', color: '#3b82f6' },
  CONFIRMATION: { label: '确认方向', color: '#8b5cf6' },
  COMPLETED: { label: '已完成', color: '#10b981' },
}

export const PLATFORM_OPTIONS = [
  { value: '公众号', label: '微信公众号' },
  { value: '小红书', label: '小红书' },
  { value: '抖音', label: '抖音' },
  { value: 'B站', label: '哔哩哔哩' },
  { value: '快手', label: '快手' },
  { value: '头条', label: '今日头条' },
]

export const TONE_OPTIONS = [
  '专业严谨', '轻松幽默', '温暖治愈', '犀利直白', '理性客观', '热情洋溢'
]
