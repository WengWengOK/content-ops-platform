/**
 * Type definitions matching the backend DTOs of Content Ops Agent Platform.
 *
 * All types correspond to Java DTOs in content-ops-common/src/main/java/com/contentops/common/
 */

// ═══════════════════════════════════════════════════════════════
//  Generic API Response Wrapper (AgentResponse<T>)
// ═══════════════════════════════════════════════════════════════

export interface AgentResponse<T = Record<string, unknown>> {
  success: boolean
  stage: string
  message: string
  data: T
  metadata?: Record<string, unknown>
  timestamp: string
  error?: string
}

// ═══════════════════════════════════════════════════════════════
//  Account Profile
// ═══════════════════════════════════════════════════════════════

export interface AccountProfile {
  accountId: string
  accountName: string
  niche: string
  targetAudience: string
  tone: string
  platforms: string[]
  personalExperience?: string
}

// ═══════════════════════════════════════════════════════════════
//  Task Context (Workflow State)
// ═══════════════════════════════════════════════════════════════

/**
 * WorkflowStatus — 对齐后端 com.contentops.common.enums.TaskStatus
 * 后端使用 TaskStatus.name() 序列化为枚举名字符串。
 */
export type WorkflowStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'AWAITING_HUMAN'
  | 'AWAITING_ASYNC'
  | 'COMPLETED'
  | 'FAILED'
  | 'BUDGET_EXCEEDED'
  | 'SKIPPED'

/**
 * StageCode — 对齐后端 com.contentops.common.enums.AgentStage#getCode()
 * 后端返回 kebab-case 编码（如 "topic-planning"），前端必须使用相同格式。
 */
export type StageCode =
  | 'topic-planning'
  | 'content-creation'
  | 'image-design'
  | 'publishing'
  | 'data-analysis'
  | 'optimization'

/**
 * 发布作品模式：text-cover（文字+封面）/ image-text（图文混排）/ full-image（全图卡片）
 */
export type PublishMode = 'text-cover' | 'image-text' | 'full-image'

/**
 * 阶段编码 → 中文名称映射（用于 UI 显示）。
 */
export const STAGE_CODE_TO_CN: Record<StageCode, string> = {
  'topic-planning': '选题策划',
  'content-creation': '内容创作',
  'image-design': '配图设计',
  'publishing': '排版发布',
  'data-analysis': '数据分析',
  'optimization': '优化迭代',
}

/**
 * WorkflowStatus → 中文标签映射（用于 UI 显示）。
 */
export const STATUS_TO_CN: Record<WorkflowStatus, string> = {
  PENDING: '等待中',
  IN_PROGRESS: '运行中',
  AWAITING_HUMAN: '待审核',
  AWAITING_ASYNC: '异步等待',
  COMPLETED: '已完成',
  FAILED: '失败',
  BUDGET_EXCEEDED: '预算用尽',
  SKIPPED: '已跳过',
}

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant'
  content: string
}

export interface TaskContext {
  workflowId: string
  currentStage: string
  currentSubStage?: string
  accountProfile: AccountProfile
  inputs: Record<string, unknown>
  outputs: Record<string, unknown>
  accumulatedArtifacts: Record<string, unknown>
  status: WorkflowStatus
  errorMessage?: string
  createdAt: string
  updatedAt: string
  requireHumanReview: boolean
  cycleCount: number
  maxCycles: number
  cycleHistory: Record<string, unknown>
  lastOptimizationFeedback?: string
  conversationHistory: ChatMessage[]
}

// ═══════════════════════════════════════════════════════════════
//  Stage Info
// ═══════════════════════════════════════════════════════════════

export interface StageInfo {
  order: number
  code: StageCode
  name: string
  description: string
}

// ═══════════════════════════════════════════════════════════════
//  Agent Task Request
// ═══════════════════════════════════════════════════════════════

export interface AgentTaskRequest {
  taskId?: string
  workflowId?: string
  stageCode?: string
  accountProfile: AccountProfile
  inputs: Record<string, unknown>
  accumulatedArtifacts?: Record<string, unknown>
  requireHumanReview?: boolean
  timestamp?: string
}

// ═══════════════════════════════════════════════════════════════
//  Topic Plan Result
// ═══════════════════════════════════════════════════════════════

export interface TopicCandidate {
  title: string
  angle: string
  rationale: string
  estimatedEngagement: number
  keywords: string[]
  platformAdaptations: Record<string, string>
}

export interface TopicPlanResult {
  topics: TopicCandidate[]
  trendingKeywords: string[]
  competitiveAnalysis: string
  recommendedDirection: string
}

// ═══════════════════════════════════════════════════════════════
//  Content Draft Result
// ═══════════════════════════════════════════════════════════════

export interface ArticleSection {
  heading: string
  /** 后端 ContentDraftResult.Section.keyPoints 为 String（以换行分隔），前端按需 split */
  keyPoints: string
  example?: string
}

export interface ArticleOutline {
  introduction: string
  sections: ArticleSection[]
  conclusion: string
}

export interface OutlineResult {
  title: string
  outline: ArticleOutline
  writingNotes: string[]
  references: string[]
  estimatedWordCount: number
  angle: string
}

export interface ContentDraftResult {
  outline: ArticleOutline
  draftContent: string
  wordCount: number
  titleVariations: string[]
  tags: string[]
  summary: string
}

// ═══════════════════════════════════════════════════════════════
//  Image Design Result
// ═══════════════════════════════════════════════════════════════

export interface StyleDirection {
  name: string
  description: string
  colorPalette: string
  promptPrefix: string
  suggestedPositions: string[]
  recommendationScore: number
}

export interface StyleDirectionResult {
  visualKeywords: string[]
  directions: StyleDirection[]
  toneAnalysis: string
}

export interface GeneratedImage {
  prompt: string
  imageUrl: string
  style: string
  colorTone: string
  position: string
}

export interface PlatformCover {
  platform: string
  imageUrl: string
  width: number
  height: number
  format: string
}

export interface ImageDesignResult {
  images: GeneratedImage[]
  covers: PlatformCover[]
}

// ═══════════════════════════════════════════════════════════════
//  Publish Result
// ═══════════════════════════════════════════════════════════════

export type PublishStatus = 'PUBLISHED' | 'DRAFT' | 'FAILED'
export type PublishOverallStatus = 'SUCCESS' | 'PARTIAL' | 'FAILED'

export interface PlatformPublication {
  platform: string
  articleUrl: string
  formattedContent: string
  status: PublishStatus
  failureReason?: string
  publishedAt: string
  platformMetadata: Record<string, unknown>
}

export interface PublishResult {
  publications: PlatformPublication[]
  status: PublishOverallStatus
}

// ═══════════════════════════════════════════════════════════════
//  Analysis Report
// ═══════════════════════════════════════════════════════════════

export interface CategoryPerformance {
  category: string
  avgReads: number
  avgLikes: number
  avgShares: number
  engagementRate: number
  articleCount: number
}

export interface TimeSlotPerformance {
  dayOfWeek: string
  timeRange: string
  avgEngagement: number
  articleCount: number
}

export interface AnalysisReport {
  keyMetrics: Record<string, number>
  categoryPerformance: CategoryPerformance[]
  timeSlotPerformance: TimeSlotPerformance[]
  insights: string[]
  recommendations: string[]
  chartData: Record<string, unknown>
}

// ═══════════════════════════════════════════════════════════════
//  Optimization Result
// ═══════════════════════════════════════════════════════════════

export type StrategyDimension =
  | 'content_type'
  | 'posting_time'
  | 'platform_focus'
  | 'tone'

export interface StrategyAdjustment {
  dimension: StrategyDimension
  currentValue: string
  recommendedValue: string
  rationale: string
  expectedImpact: number
}

export interface OptimizationResult {
  strategyAdjustments: StrategyAdjustment[]
  recommendedTopics: string[]
  learnings: string[]
  healthScore: number
  cycleSummary: string
}

// ═══════════════════════════════════════════════════════════════
//  Discussion Types
// ═══════════════════════════════════════════════════════════════

export type DiscussionPhase =
  | 'IDEATION'
  | 'CLARIFICATION'
  | 'CONFIRMATION'
  | 'COMPLETED'

export interface DiscussionResponse {
  sessionId: string
  phase: DiscussionPhase
  message: string
  clarifyingQuestions: string[]
  proposedDirections: string[]
  canFinalize: boolean
  turnCount: number
}

export interface DiscussionTurn {
  role: 'system' | 'user' | 'assistant'
  content: string
  timestamp: string
}

export interface DiscussionSession {
  sessionId: string
  workflowId?: string
  phase: DiscussionPhase
  fuzzyIdea: string
  accountProfile: AccountProfile
  turns: DiscussionTurn[]
  clarifyingQuestions: string[]
  proposedDirections: string[]
  confirmedDirection?: string
  topicPlanResult?: TopicPlanResult
  createdAt: string
  updatedAt: string
}

// ═══════════════════════════════════════════════════════════════
//  Request DTOs for Orchestrator
// ═══════════════════════════════════════════════════════════════

export interface StartWorkflowRequest {
  accountProfile: AccountProfile
  inputs: Record<string, unknown>
  collectionIds?: string[]
  platformAccounts?: Record<string, PlatformAccountInfo>
  requireHumanReview?: boolean
  publishMode?: PublishMode
}

export interface CollectionWork {
  workflowId: string
  title: string
  status?: string
  platforms?: string[]
  publishMode?: string
  createdAt?: string
}

export interface WorkCollection {
  collectionId: string
  ownerId?: string
  name: string
  type: string
  description?: string
  createdAt?: string
  updatedAt?: string
  workCount?: number
  works?: CollectionWork[]
}

export interface TrendHotspot {
  id: string
  platform: string
  title: string
  url?: string
  heat?: number
  rank?: number
  category?: string
  summary?: string
  capturedAt?: string
  /** AI 分析结果（相关性/可信度/摘要），搜索与关注视图附带 */
  analysis?: {
    relevance?: number
    credibility?: number
    summary?: string
    riskFlag?: boolean
  }
  /** 突发热点标记：新上榜 / 飙升 / 上升 */
  burstLabel?: string
  /** 较上一快照热度增量 */
  heatDelta?: number
  /** 较上一快照排名上升数 */
  rankDelta?: number
  /** 是否首次上榜 */
  isNew?: boolean
  /** 首次出现时间 */
  firstSeenAt?: string
  /** 爆发得分 */
  burstScore?: number
}

export interface TrendSubscription {
  subscriptionId: string
  ownerId?: string
  keyword: string
  enabled?: boolean
  createdAt?: string
}

/** 关键词命中记录：启用中的监控方向匹配到的热点（支撑突发热点检测/通知） */
export interface TrendKeywordHit {
  hitId: string
  ownerId?: string
  keyword: string
  platform: string
  title: string
  url?: string
  heat?: number
  rank?: number
  category?: string
  summary?: string
  capturedAt?: string
}

/** 突发热点事件：轮询时检测到的新上榜/飙升/上升 */
export interface TrendBurstEvent {
  eventId: string
  platform: string
  title: string
  url?: string
  heat?: number
  prevHeat?: number
  rank?: number
  prevRank?: number
  heatDelta?: number
  rankDelta?: number
  burstLabel: string
  burstScore?: number
  capturedAt?: string
}

/** 全网搜索聚合条目 */
export interface WebSearchHit {
  source: string
  title?: string
  url?: string
  content?: string
  score?: number
}

/** 主题趋势：单平台热度/排名时间点 */
export interface TrendHistoryPoint {
  capturedAt?: string
  heat?: number
  rank?: number
}

/** 主题趋势：跨平台最近热度 */
export interface TrendPlatformHeat {
  platform: string
  heat?: number
  rank?: number
  url?: string
}

/** LLM 调用追踪 */
export interface LlmTrace {
  traceId: string
  workflowId?: string
  stage?: string
  agent?: string
  model?: string
  tokensIn?: number
  tokensOut?: number
  promptChars?: number
  outputChars?: number
  latencyMs?: number
  status: string
  errorMessage?: string
  createdAt?: string
  /** OpenTelemetry Trace ID */
  otelTraceId?: string
  /** OpenTelemetry Span ID */
  otelSpanId?: string
}

/** LLM 统计大盘 */
export interface LlmStats {
  hours: number
  calls: number
  tokensIn: number
  tokensOut: number
  avgLatencyMs: number
  errors: number
  errorRate: number
  estimatedCostUsd: number
  byStageAgent: {
    stage: string
    agent: string
    calls: number
    tokens_in: number
    tokens_out: number
    avg_latency_ms: number
    errors: number
  }[]
  timeseries: { bucket: string; calls: number; tokens_in: number; tokens_out: number }[]
}

/**
 * 选题确认后选择发布平台（多平台时后端扇出并行分支）。
 */
export interface SelectPlatformsRequest {
  platforms: string[]
  topic?: string
  customTopic?: string
  platformAccounts?: Record<string, PlatformAccountInfo>
}

export interface PlatformAccountInfo {
  accountId: string
  accountName: string
}

/**
 * 多平台并行分支元数据（位于父工作流 inputs.branches）。
 */
export interface WorkflowBranch {
  platform: string
  platformName: string
  workflowId: string
  status: string
  accountName?: string
  currentStage?: string
}

export interface DiscussStartRequest {
  fuzzyIdea: string
  accountProfile: AccountProfile
}

export interface DiscussChatRequest {
  message: string
}
