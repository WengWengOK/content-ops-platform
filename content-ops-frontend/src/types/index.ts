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

export type WorkflowStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'WAITING_FOR_REVIEW'
  | 'COMPLETED'
  | 'FAILED'

export type StageCode =
  | 'TOPIC_PLANNING'
  | 'CONTENT_CREATION'
  | 'IMAGE_DESIGN'
  | 'PUBLISHING'
  | 'DATA_ANALYSIS'
  | 'OPTIMIZATION'

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
  keyPoints: string[]
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
  requireHumanReview?: boolean
}

export interface DiscussStartRequest {
  fuzzyIdea: string
  accountProfile: AccountProfile
}

export interface DiscussChatRequest {
  message: string
}
