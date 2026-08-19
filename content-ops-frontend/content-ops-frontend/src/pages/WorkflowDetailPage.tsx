import { useEffect, useRef, useState, useCallback } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import {
  selectPlatforms,
  downloadWorkflow,
  runStandaloneAnalysis,
  runStandaloneOptimize,
  listWorkflowDiscussions,
  startWorkflowDiscussion,
  chatDiscussion,
  applyDiscussionModification,
  uploadWorkflowCover,
  uploadWorkflowMaterial,
  updateWorkflowContent,
} from '@/api/workflow'
import {
  listCollections,
  listCollectionsByWorkflow,
  addWorkToCollection,
  removeWorkFromCollection,
} from '@/api/collections'
import { useWorkflowStatus, useApproveStage, useConfirmSubStage } from '@/hooks/useWorkflow'
import { LoadingView, ErrorView } from '@/components/common/StateViews'
import { MarkdownView } from '@/components/common/MarkdownView'
import type {
  StageCode,
  TaskContext,
  WorkflowBranch,
  PlatformAccountInfo,
  DiscussionSession,
  WorkCollection,
} from '@/types'
import { STAGE_CODE_TO_CN } from '@/types'

/* ============================================================
   Types
   ============================================================ */
type StageStatus = 'completed' | 'running' | 'pending'
type ConnectorType = 'done' | 'active' | 'idle'
type ChatMessageType = 'system' | 'agent' | 'user'

interface ChatMessage {
  id: number
  type: ChatMessageType
  content: string
  time?: string
  suggestions?: string[]
  suggestionTitle?: string
}

interface PipelineStage {
  id: string
  name: string
  agent: string
  status: StageStatus
}

interface ReviewTopic {
  title: string
  tags: string[]
}

/* ============================================================
   Mock data
   ============================================================ */
const PIPELINE_STAGES: PipelineStage[] = [
  { id: 'topic-planning', name: '选题策划', agent: '选题 Agent', status: 'completed' },
  { id: 'content-creation', name: '内容创作', agent: '内容 Agent', status: 'running' },
  { id: 'image-design', name: '配图设计', agent: '配图 Agent', status: 'pending' },
  { id: 'publishing', name: '排版发布', agent: '发布 Agent', status: 'pending' },
]

const LEGEND: { label: string; color: string; pulse?: boolean }[] = [
  { label: '已完成', color: '#00B42A' },
  { label: '运行中', color: '#165DFF', pulse: true },
  { label: '待审核', color: '#FF7D00' },
  { label: '等待中', color: '#C9CDD4' },
]

const PLATFORM_OPTIONS: { code: string; name: string; color: string }[] = [
  { code: 'xiaohongshu', name: '小红书', color: '#FF2D5E' },
  { code: 'wechat', name: '微信公众号', color: '#165DFF' },
  { code: 'douyin', name: '抖音', color: '#FE2C55' },
  { code: 'bilibili', name: '哔哩哔哩', color: '#00A1D6' },
]

const PREVIEW_TAGS = ['自律', '个人成长', '习惯养成', '100天挑战', '真实经历']

const MODULE_LABELS: Record<string, string> = {
  title: '标题',
  cover: '封面',
  content: '正文内容',
  layout: '排版样式',
  tags: '标签',
}

/* ============================================================
   Inline SVG icons
   ============================================================ */
type IconProps = { className?: string; style?: CSSProperties }

function Svg({
  children,
  className,
  style,
  sw = 1.8,
}: {
  children: ReactNode
  className?: string
  style?: CSSProperties
  sw?: number
}) {
  return (
    <svg
      className={className}
      style={style}
      fill="none"
      stroke="currentColor"
      strokeWidth={sw}
      viewBox="0 0 24 24"
      strokeLinecap="round"
      strokeLinejoin="round"
      xmlns="http://www.w3.org/2000/svg"
    >
      {children}
    </svg>
  )
}

const IconChevronLeft = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M15.75 19.5 8.25 12l7.5-7.5" />
  </Svg>
)
const IconHash = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M5.25 8.25h15m-16.5 7.5h15m-1.8-13.5-3.9 19.5m-2.1-19.5-3.9 19.5" />
  </Svg>
)
const IconClock = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 6v6h4.5" />
  </Svg>
)
const IconUser = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0" />
  </Svg>
)
const IconWarn = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
  </Svg>
)
const IconWarnTriangle = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M10.3 3.3 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.3a2 2 0 0 0-3.4 0Z" />
    <path d="M12 9v4M12 17h.01" />
  </Svg>
)
const IconClose = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M6 18 18 6M6 6l12 12" />
  </Svg>
)
const IconCheck = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M4.5 12.75l6 6 9-13.5" />
  </Svg>
)
const IconPen = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125" />
  </Svg>
)
const IconRefresh = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182" />
  </Svg>
)
const IconRestore = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M9 15 3 9m0 0 6-6M3 9h12a6 6 0 0 1 0 12h-3" />
  </Svg>
)
const IconSend = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" />
  </Svg>
)
const IconMaximize = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15" />
  </Svg>
)
const IconEye = (p: IconProps) => (
  <Svg {...p} sw={2}>
    <path d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z" />
    <path d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
  </Svg>
)
const IconImage = (p: IconProps) => (
  <Svg {...p} sw={1.5}>
    <path d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909m-18 3.75h16.5a1.5 1.5 0 0 0 1.5-1.5V6a1.5 1.5 0 0 0-1.5-1.5H3.75A1.5 1.5 0 0 0 2.25 6v12a1.5 1.5 0 0 0 1.5 1.5Zm10.5-11.25h.008v.008h-.008V8.25Zm.375 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Z" />
  </Svg>
)
const IconPhoneOff = (p: IconProps) => (
  <Svg {...p} sw={1.5}>
    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92Z" />
  </Svg>
)

/* Filled check-circle used by completed stage badge label */
function CheckCircleFilled({ className }: { className?: string }) {
  return (
    <svg className={className} fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm3.857-9.809a.75.75 0 0 0-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 1 0-1.06 1.061l2.5 2.5a.75.75 0 0 0 1.137-.089l4-5.5Z"
        clipRule="evenodd"
      />
    </svg>
  )
}

/* Filled warning badge used by review "需要你的确认" pill */
function WarnFilled({ className }: { className?: string }) {
  return (
    <svg className={className} fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495ZM10 5a.75.75 0 0 1 .75.75v3.5a.75.75 0 0 1-1.5 0v-3.5A.75.75 0 0 1 10 5Zm0 9a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z"
        clipRule="evenodd"
      />
    </svg>
  )
}

function Spinner({ size = 14, color = '#165DFF', sw = 3 }: { size?: number; color?: string; sw?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className="animate-spin"
      stroke={color}
      strokeWidth={sw}
    >
      <path strokeLinecap="round" d="M12 2a10 10 0 0 1 10 10" />
    </svg>
  )
}

/* ============================================================
   Preview module wrapper
   ============================================================ */
function PreviewModule({
  module,
  selected,
  onSelect,
  children,
}: {
  module: string
  selected: boolean
  onSelect: (m: string) => void
  children: ReactNode
}) {
  return (
    <div
      className={`preview-module relative cursor-pointer ${selected ? 'is-selected' : ''}`}
      onClick={() => onSelect(module)}
      style={{
        outline: selected ? '2px solid #165DFF' : undefined,
        outlineOffset: '-2px',
        background: selected ? 'rgba(22,93,255,0.03)' : undefined,
      }}
    >
      {children}
      <button
        className="module-edit-icon absolute right-2 top-2 flex h-7 w-7 items-center justify-center rounded-md transition-opacity"
        style={{
          background: '#165DFF',
          color: '#FFFFFF',
          opacity: selected ? 1 : 0,
          boxShadow: '0 2px 6px rgba(0,0,0,0.12)',
          pointerEvents: 'none',
          zIndex: 10,
        }}
        tabIndex={-1}
      >
        <IconPen className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

/* ============================================================
   Main component
   ============================================================ */
export function WorkflowDetailPage() {
  const [searchParams] = useSearchParams()
  const workflowId = searchParams.get('workflowId')

  // 工作流状态轮询
  const { workflow, loading, error, refresh } = useWorkflowStatus(workflowId, { intervalMs: 5000 })
  const branches: WorkflowBranch[] = Array.isArray(workflow?.inputs?.branches)
    ? (workflow!.inputs.branches as unknown as WorkflowBranch[])
    : []
  const [activeBranchId, setActiveBranchId] = useState<string | null>(null)
  const awaitingPlatformSelection =
    workflow?.status === 'AWAITING_HUMAN' &&
    workflow?.currentStage === 'topic-planning' &&
    branches.length === 0
  // 子阶段人工确认（内容大纲 / 配图风格），走 confirm-substage 而非 approve
  const awaitingSubStage =
    workflow?.status === 'AWAITING_HUMAN' &&
    !!workflow?.currentSubStage &&
    !awaitingPlatformSelection
  const reviewStageDef = PIPELINE_STAGES.find((s) => s.id === (workflow?.currentStage ?? ''))
  const reviewStageName = reviewStageDef?.name ?? workflow?.currentStage ?? '选题策划'
  const reviewStageAgent = reviewStageDef?.agent ?? 'Agent'
  const [selectedPlatforms, setSelectedPlatforms] = useState<string[]>([])
  const [submittingSelection, setSubmittingSelection] = useState(false)
  useEffect(() => {
    if (!awaitingPlatformSelection || selectedPlatforms.length > 0) return
    const preset = Array.isArray(workflow?.inputs?.platforms)
      ? (workflow!.inputs.platforms as string[])
      : []
    setSelectedPlatforms(preset)
  }, [awaitingPlatformSelection, workflow?.inputs?.platforms])
  const togglePlatform = (code: string) => {
    setSelectedPlatforms((prev) =>
      prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]
    )
  }
  const handleSelectPlatforms = async () => {
    if (!workflowId || selectedPlatforms.length === 0) {
      showToast('请至少选择一个平台', '#FF7D00')
      return
    }
    setSubmittingSelection(true)
    try {
      await selectPlatforms(workflowId, {
        platforms: selectedPlatforms,
        platformAccounts:
          (workflow?.inputs?.platformAccounts as Record<string, PlatformAccountInfo> | undefined) ?? undefined,
        topic:
          selectedTopicIndex != null ? reviewTopics[selectedTopicIndex]?.title : undefined,
        customTopic: customTopic.trim() || undefined,
      })
      showToast('平台已确认，流水线开始产出', '#00B42A')
      refresh()
    } catch (err: any) {
      showToast(err?.message || '平台确认失败', '#F53F3F')
    } finally {
      setSubmittingSelection(false)
    }
  }

  /* ── 用户上传封面替换 AI 封面 ── */
  const handleUploadCover = async (e: any) => {
    const file = e.target?.files?.[0]
    e.target.value = ''
    if (!workflowId || !file) return
    setActionLoading('cover')
    try {
      await uploadWorkflowCover(workflowId, file)
      showToast('封面已替换为上传图片', '#00B42A')
      refresh()
    } catch (err: any) {
      showToast(err?.message || '封面上传失败', '#F53F3F')
    } finally {
      setActionLoading(null)
    }
  }

  /* ── 上传创作素材（AI 自动分析选题/创作） ── */
  const handleUploadMaterial = async (e: any) => {
    const file = e.target?.files?.[0]
    e.target.value = ''
    if (!workflowId || !file) return
    setActionLoading('material')
    try {
      const r = await uploadWorkflowMaterial(workflowId, file)
      showToast(
        r.textExtracted
          ? `素材「${r.name}」已上传并提取内容`
          : `素材「${r.name}」已上传（仅保存引用）`,
        '#00B42A'
      )
      refresh()
    } catch (err: any) {
      showToast(err?.message || '素材上传失败', '#F53F3F')
    } finally {
      setActionLoading(null)
    }
  }

  /* ── 确定性修改标题/正文（可视化编辑直接落库） ── */
  const handleSaveContentEdit = async () => {
    if (!workflowId) return
    setActionLoading('edit')
    try {
      const body: { title?: string; content?: string } = {}
      if (selectedModule === 'title') body.title = editTitle
      if (selectedModule === 'content') body.content = editContent
      await updateWorkflowContent(workflowId, body)
      showToast('修改已保存，下载/渲染将使用新版本', '#00B42A')
      refresh()
      setSelectedModule(null)
    } catch (err: any) {
      showToast(err?.message || '保存失败', '#F53F3F')
    } finally {
      setActionLoading(null)
    }
  }
  const detailId = branches.length > 0
    ? (activeBranchId ?? branches[0]?.workflowId ?? workflowId!)
    : workflowId!
  const branchStatus = useWorkflowStatus(
    branches.length > 0 ? detailId : null,
    { intervalMs: 5000, stopOnTerminal: false }
  )
  const detail = branches.length > 0 ? (branchStatus.workflow ?? workflow) : workflow
  const { approving, approve } = useApproveStage()
  const { confirming, confirm } = useConfirmSubStage()

  // ── 作品聊天续改（会话与作品归属 + 查看聊天记录 + 应用修改） ──
  const [chatOpen, setChatOpen] = useState(false)
  const [chatSessions, setChatSessions] = useState<DiscussionSession[]>([])
  const [chatSessionId, setChatSessionId] = useState<string | null>(null)
  const [chatMessages, setChatMessages] = useState<{ role: 'user' | 'assistant'; content: string }[]>([])
  const [chatInput, setChatInput] = useState('')
  const [chatBusy, setChatBusy] = useState(false)

  // ── 作品合集：生成后把作品加入/移出合集 ──
  const [collectionOpen, setCollectionOpen] = useState(false)
  const [allCollections, setAllCollections] = useState<WorkCollection[]>([])
  const [workCollectionIds, setWorkCollectionIds] = useState<string[]>([])
  const [collectionBusy, setCollectionBusy] = useState(false)

  // ── 用户上传 / 确定性编辑 / 选题选择 ──
  const [editTitle, setEditTitle] = useState('')
  const [editContent, setEditContent] = useState('')
  const [selectedTopicIndex, setSelectedTopicIndex] = useState<number | null>(null)
  const [customTopic, setCustomTopic] = useState('')
  const coverInputRef = useRef<HTMLInputElement>(null)
  const materialInputRef = useRef<HTMLInputElement>(null)

  // 根据后端 TaskContext 计算各阶段状态
  const computeStageStatus = useCallback(
    (stageId: string): StageStatus => {
      if (!detail) return 'pending'
      const stageOrder: Record<string, number> = {
        'topic-planning': 1,
        'content-creation': 2,
        'image-design': 3,
        'publishing': 4,
      }
      const currentOrder = stageOrder[detail.currentStage] || 0
      const targetOrder = stageOrder[stageId] || 0
      if (targetOrder < currentOrder) return 'completed'
      if (targetOrder === currentOrder) {
        if (detail.status === 'COMPLETED') return 'completed'
        if (detail.status === 'FAILED') return 'pending'
        return 'running'
      }
      return 'pending'
    },
    [detail]
  )

  // 动态生成阶段列表
  const pipelineStages: PipelineStage[] = PIPELINE_STAGES.map((s) => ({
    ...s,
    status: computeStageStatus(s.id),
  }))

  // 侧栏聊天：真实会话驱动（按 workflowId 隔离），不使用演示数据
  const msgSeqRef = useRef(0)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [inputValue, setInputValue] = useState('')
  const [inputFocused, setInputFocused] = useState(false)
  const [selectedModule, setSelectedModule] = useState<string | null>(null)
  // 默认隐藏，仅在后端处于「需要人工确认」状态时自动弹出（避免状态未加载时误点）
  const [showReviewPanel, setShowReviewPanel] = useState(false)
  const [showFeedbackArea, setShowFeedbackArea] = useState(false)
  const [feedbackText, setFeedbackText] = useState('')
  // 审核面板数据来自真实工作流产物（选题方案 / 质量检查清单），无数据时展示空态
  const topicArtifacts = (detail?.accumulatedArtifacts?.['topic-planning'] ?? {}) as Record<string, unknown>
  const reviewTopics: ReviewTopic[] = Array.isArray(topicArtifacts.topics)
    ? (topicArtifacts.topics as Array<{ title?: string; keywords?: unknown }>).map((t) => ({
        title: t.title ?? '未命名选题',
        tags: Array.isArray(t.keywords)
          ? t.keywords.filter((k): k is string => typeof k === 'string')
          : [],
      }))
    : []
  const reviewChecklist: string[] = Array.isArray(detail?.accumulatedArtifacts?.['topic-planning:checklist'])
    ? (detail.accumulatedArtifacts['topic-planning:checklist'] as string[])
    : []
  const competitiveAnalysis =
    typeof topicArtifacts.competitiveAnalysis === 'string' ? topicArtifacts.competitiveAnalysis : ''
  // 作品标题/正文（用于可视化编辑初始值与确定性修改）
  const workArtifacts = (detail?.accumulatedArtifacts ?? {}) as Record<string, unknown>
  const draftArtifact = workArtifacts['content-creation:draft'] as Record<string, unknown> | undefined
  const contentArtifact = workArtifacts['content-creation'] as Record<string, unknown> | undefined
  const publishingArtifact = workArtifacts['publishing'] as Record<string, unknown> | undefined
  const outlineArtifact = workArtifacts['content-creation:outline'] as Record<string, unknown> | undefined
  const draftTitleVariations = Array.isArray(draftArtifact?.titleVariations)
    ? (draftArtifact.titleVariations as string[])
    : []
  const workTitle = String(
    publishingArtifact?.articleTitle ??
      draftTitleVariations[0] ??
      draftArtifact?.title ??
      String(outlineArtifact?.title ?? '') ??
      topicArtifacts.topic ??
      topicArtifacts.recommendedDirection ??
      ''
  )
  const workContent = String(
    publishingArtifact?.articleContent ??
      draftArtifact?.draftContent ??
      contentArtifact?.content ??
      contentArtifact?.draftContent ??
      ''
  )
  const coverUrl = String(publishingArtifact?.coverImageUrl ?? '')
  // 阶段决策数据：大纲 / 风格 / 发布校验与质量
  const styleArtifact = workArtifacts['image-design:styles'] as Record<string, unknown> | undefined
  const styleDirections: Array<{ name?: string; description?: string; keywords?: unknown }> =
    Array.isArray(styleArtifact?.styleDirections)
      ? (styleArtifact.styleDirections as Array<{ name?: string; description?: string; keywords?: unknown }>)
      : Array.isArray(styleArtifact?.directions)
        ? (styleArtifact.directions as Array<{ name?: string; description?: string; keywords?: unknown }>)
        : []
  const outlineObj = (outlineArtifact?.outline ?? {}) as { sections?: Array<{ heading?: string; keyPoints?: string }> }
  const outlineSections: Array<{ heading?: string; keyPoints?: string }> =
    Array.isArray(outlineObj.sections) ? outlineObj.sections : []
  const publishQuality = workArtifacts['publishing:quality'] as Record<string, unknown> | undefined
  const publishChecklist = Array.isArray(workArtifacts['publishing:checklist'])
    ? (workArtifacts['publishing:checklist'] as string[])
    : []
  const decisionKind =
    detail?.status === 'COMPLETED' || detail?.currentStage === 'publishing'
      ? 'publish'
      : detail?.currentStage === 'topic-planning'
        ? 'topic'
        : detail?.currentStage === 'content-creation' && detail?.currentSubStage === 'outline'
          ? 'outline'
          : detail?.currentStage === 'image-design' && detail?.currentSubStage === 'styles'
            ? 'styles'
            : null
  const [selectedStyle, setSelectedStyle] = useState<string | null>(null)
  useEffect(() => {
    if (styleDirections.length > 0 && selectedStyle == null) {
      setSelectedStyle(styleDirections[0].name ?? null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [styleDirections.length])
  useEffect(() => {
    if (selectedModule === 'title') setEditTitle(workTitle)
    if (selectedModule === 'content') setEditContent(workContent)
  }, [selectedModule, workTitle, workContent])
  const [checklist, setChecklist] = useState<boolean[]>(
    reviewChecklist.map(() => false),
  )
  const [showError, setShowError] = useState(false)
  const [retryCountdown, setRetryCountdown] = useState(3)
  const [fullscreen, setFullscreen] = useState(false)
  const [selectedStage, setSelectedStage] = useState<string | null>(null)
  const [reviewSeconds, setReviewSeconds] = useState(32)
  const [toast, setToast] = useState<{ msg: string; color: string } | null>(null)
  const [actionLoading, setActionLoading] = useState<string | null>(null)

  const chatScrollRef = useRef<HTMLDivElement>(null)

  /* ── chat helpers ── */
  const nowTime = () => {
    const n = new Date()
    const p = (x: number) => x.toString().padStart(2, '0')
    return `${p(n.getHours())}:${p(n.getMinutes())}:${p(n.getSeconds())}`
  }
  const appendMessage = (m: Omit<ChatMessage, 'id'>): number => {
    msgSeqRef.current += 1
    const id = msgSeqRef.current
    setMessages((prev) => [...prev, { id, ...m }])
    return id
  }
  const showToast = (msg: string, color = '#00B42A') => {
    setToast({ msg, color })
    window.setTimeout(() => setToast(null), 3000)
  }

  /* ── 聊天会话隔离：切换工作流时重置并加载该作品的真实聊天记录 ── */
  useEffect(() => {
    setMessages([])
    msgSeqRef.current = 0
    setChatSessionId(null)
    setChatMessages([])
    setChatSessions([])
    setShowReviewPanel(false)
    if (!workflowId) return
    let cancelled = false
    listWorkflowDiscussions(workflowId)
      .then((sessions) => {
        if (cancelled) return
        setChatSessions(sessions)
        const first = sessions[0]
        if (first && Array.isArray(first.turns) && first.turns.length > 0) {
          setChatSessionId(first.sessionId)
          const turns = first.turns.map((t) => ({
            role: t.role === 'user' ? ('user' as const) : ('assistant' as const),
            content: t.content ?? '',
          }))
          setChatMessages(turns)
          turns.forEach((t) => {
            appendMessage({
              type: t.role === 'user' ? 'user' : 'agent',
              time: nowTime(),
              content: t.content,
            })
          })
        }
      })
      .catch(() => {
        /* 无会话或加载失败时保持空聊天 */
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflowId])

  /* ── 工作流阶段事件 SSE：阶段推进实时写入聊天 + 刷新状态 ── */
  useEffect(() => {
    if (!workflowId) return
    const es = new EventSource(`/api/v1/workflow/${encodeURIComponent(workflowId)}/events`)
    es.addEventListener('stage', (e: MessageEvent) => {
      try {
        const ev = JSON.parse(e.data)
        const stageCode = ev.fromStage ?? ev.toStage ?? ''
        const label =
          ev.eventType === 'STAGE_COMPLETED'
            ? `阶段「${stageCode}」已完成，进入「${ev.toStage ?? ''}」`
            : ev.eventType === 'STAGE_STARTED'
              ? `阶段「${ev.toStage ?? ''}」开始执行`
              : `阶段「${ev.fromStage ?? ''}」执行失败${ev.errorMessage ? '：' + ev.errorMessage : ''}`
        appendMessage({ type: 'system', content: `${nowTime()} - ${label}` })
        if (ev.eventType === 'STAGE_STARTED') {
          setLiveStage(stageCode)
          setLiveOutput('')
          setLiveTools([])
        } else if (ev.eventType === 'STAGE_COMPLETED' && ev.artifactSummary) {
          setLiveStage(stageCode)
          streamText(artifactToText(stageCode, ev.artifactSummary))
        } else if (ev.eventType === 'TOOL_CALLED') {
          setLiveStage(stageCode)
          setLiveTools((prev) => [...prev.slice(-9), { tool: String(ev.tool ?? 'tool'), time: nowTime() }])
          appendMessage({ type: 'system', content: `${nowTime()} - 🔧 调用工具：${ev.tool ?? ''}${ev.args ? ' ' + ev.args : ''}` })
        }
        refresh()
      } catch {
        /* 忽略无法解析的事件 */
      }
    })
    return () => es.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflowId])

  /* ── 实时产出：阶段产物打字机流式展示 ── */
  const [liveStage, setLiveStage] = useState<string | null>(null)
  const [liveOutput, setLiveOutput] = useState('')
  const [liveTools, setLiveTools] = useState<{ tool: string; time: string }[]>([])
  const liveTimerRef = useRef<number | null>(null)

  const artifactToText = (stage: string, artifact: Record<string, unknown>): string => {
    if (!artifact) return ''
    const topics = Array.isArray(artifact.topics)
      ? (artifact.topics as Array<{ title?: string; angle?: string }>)
      : []
    if (stage === 'topic-planning') {
      const lines: string[] = []
      if (topics.length > 0) {
        lines.push(`✅ 选题方案（${topics.length} 个）`)
        topics.forEach((t, i) => lines.push(`${i + 1}. ${t.title ?? ''}${t.angle ? ' —— ' + t.angle : ''}`))
      }
      if (artifact.recommendedDirection) lines.push(`\n📌 推荐方向：${artifact.recommendedDirection}`)
      if (artifact.selectedTopic) lines.push(`\n🎯 选定选题：${artifact.selectedTopic}`)
      return lines.join('\n')
    }
    if (stage === 'content-creation') {
      const title = String(artifact.title ?? artifact.topic ?? '')
      const intro =
        artifact.outline && typeof artifact.outline === 'object'
          ? String((artifact.outline as Record<string, unknown>).introduction ?? '')
          : ''
      const draft = String(artifact.draftContent ?? '')
      if (draft) return `${title ? title + '\n\n' : ''}${draft.slice(0, 1600)}${draft.length > 1600 ? '\n…' : ''}`
      if (intro) return `${title ? title + '\n\n' : ''}大纲引言：${intro.slice(0, 400)}`
      return title || JSON.stringify(artifact).slice(0, 800)
    }
    if (stage === 'publishing') {
      const title = String(artifact.articleTitle ?? '')
      const content = String(artifact.articleContent ?? '')
      const images = Array.isArray(artifact.images) ? artifact.images.length : 0
      return `${title ? title + '\n\n' : ''}${content.slice(0, 1400)}${content.length > 1400 ? '\n…' : ''}${images ? `\n\n🖼 配图 ${images} 张` : ''}`
    }
    const raw = JSON.stringify(artifact)
    return raw.length > 800 ? raw.slice(0, 800) + '…' : raw
  }

  const streamText = (text: string) => {
    if (liveTimerRef.current) window.clearInterval(liveTimerRef.current)
    setLiveOutput('')
    if (!text) return
    let i = 0
    liveTimerRef.current = window.setInterval(() => {
      i = Math.min(i + 24, text.length)
      setLiveOutput(text.slice(0, i))
      if (i >= text.length && liveTimerRef.current) {
        window.clearInterval(liveTimerRef.current)
        liveTimerRef.current = null
      }
    }, 16)
  }

  // 进入页面时若已有产物，直接把最近阶段产物展示出来（不重放打字机）
  const seededLiveRef = useRef(false)
  useEffect(() => {
    if (seededLiveRef.current || !detail?.accumulatedArtifacts || liveOutput) return
    const arts = detail.accumulatedArtifacts as Record<string, unknown>
    const candidates: Array<[string, Record<string, unknown>]> = [
      ['publishing', arts['publishing'] as Record<string, unknown>],
      ['content-creation', arts['content-creation:draft'] as Record<string, unknown>],
      ['content-creation', arts['content-creation:outline'] as Record<string, unknown>],
      ['topic-planning', arts['topic-planning'] as Record<string, unknown>],
    ]
    for (const [stage, artifact] of candidates) {
      if (artifact) {
        setLiveStage(stage)
        setLiveOutput(artifactToText(stage, artifact))
        seededLiveRef.current = true
        break
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.accumulatedArtifacts])

  const handleSend = async () => {
    const text = inputValue.trim()
    if (!workflowId || !text || chatBusy) return
    setInputValue('')
    setChatBusy(true)
    appendMessage({ type: 'user', time: nowTime(), content: text })
    try {
      let sid = chatSessionId
      if (!sid) {
        const started = await startWorkflowDiscussion(workflowId, text)
        sid = started.sessionId
        setChatSessionId(sid)
        appendMessage({ type: 'agent', time: nowTime(), content: started.message })
        setChatSessions(await listWorkflowDiscussions(workflowId).catch(() => []))
        setChatBusy(false)
        return
      }
      // 已有会话：SSE 流式输出
      const msgId = appendMessage({ type: 'agent', time: nowTime(), content: '' })
      const es = new EventSource(
        `/api/v1/discussion/${encodeURIComponent(sid)}/chat/stream?message=${encodeURIComponent(text)}`
      )
      es.addEventListener('delta', (e: MessageEvent) => {
        setMessages((prev) =>
          prev.map((m) => (m.id === msgId ? { ...m, content: m.content + (e.data ?? '') } : m))
        )
      })
      es.addEventListener('tool', (e: MessageEvent) => {
        appendMessage({ type: 'system', content: `🔧 调用工具：${e.data ?? ''}` })
      })
      es.addEventListener('done', async () => {
        es.close()
        setChatSessions(await listWorkflowDiscussions(workflowId).catch(() => []))
        setChatBusy(false)
      })
      es.onerror = () => {
        es.close()
        setChatBusy(false)
      }
    } catch (err: any) {
      showToast(err?.message || '发送失败，请重试', '#F53F3F')
      setChatBusy(false)
    }
  }

  const handleSuggestionAction = (action: 'accept' | 'reject' | 'regenerate') => {
    if (action === 'accept') {
      appendMessage({ type: 'user', time: nowTime(), content: '采纳标题方案 A' })
      showToast('已采纳标题方案，Agent 将继续创作', '#00B42A')
    } else if (action === 'reject') {
      appendMessage({ type: 'user', time: nowTime(), content: '这几个标题都不太满意' })
      showToast('已拒绝，请提供更具体的方向', '#FF7D00')
    } else {
      appendMessage({ type: 'agent', time: nowTime(), content: '好的，正在为您重新生成 3 个标题方案，请稍候…' })
      showToast('正在重新生成标题方案…', '#165DFF')
    }
  }

  /* ── auto scroll chat to bottom ── */
  useEffect(() => {
    const el = chatScrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  /* ── error auto-retry countdown ── */
  useEffect(() => {
    if (!showError) return
    setRetryCountdown(3)
    const timer = window.setInterval(() => {
      setRetryCountdown((c) => Math.max(0, c - 1))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [showError])

  useEffect(() => {
    if (showError && retryCountdown === 0) setShowError(false)
  }, [showError, retryCountdown])

  /* ── review wait timer ── */
  useEffect(() => {
    if (!showReviewPanel) return
    const t = window.setInterval(() => setReviewSeconds((s) => s + 1), 1000)
    return () => window.clearInterval(t)
  }, [showReviewPanel])

  /* ── review handlers ── */
  const toggleChecklist = (idx: number) => {
    setChecklist((prev) => prev.map((v, i) => (i === idx ? !v : v)))
  }
  const handleApprove = async () => {
    if (!workflow || workflow.status !== 'AWAITING_HUMAN') {
      showToast('当前阶段无需人工审批', '#FF7D00')
      return
    }
    if (awaitingPlatformSelection) {
      showToast('请先在下方选择发布平台', '#FF7D00')
      return
    }
    if (!workflowId) {
      showToast('缺少 workflowId', '#F53F3F')
      return
    }
    const success = await approve(detailId)
    if (success) {
      showToast('阶段已通过，管线继续执行', '#00B42A')
      setShowReviewPanel(false)
      refresh()
    } else {
      showToast('审批失败，请重试', '#F53F3F')
    }
  }
  const handleSubmitFeedback = async () => {
    if (!feedbackText.trim()) {
      showToast('请输入修改意见', '#FF7D00')
      return
    }
    if (!workflowId) {
      showToast('缺少 workflowId', '#F53F3F')
      return
    }
    // 用反馈意见作为 feedback 参数调用 approve
    const success = await approve(detailId, { feedback: feedbackText })
    if (success) {
      showToast('修改意见已提交，Agent 将重新执行', '#165DFF')
      setFeedbackText('')
      setShowFeedbackArea(false)
      refresh()
    } else {
      showToast('提交失败，请重试', '#F53F3F')
    }
  }
  const handleConfirmSubStage = async (body?: Record<string, unknown>) => {
    if (!workflow || workflow.status !== 'AWAITING_HUMAN' || !workflow.currentSubStage) {
      showToast('当前无可确认的子阶段', '#FF7D00')
      return
    }
    if (!workflowId) {
      showToast('缺少 workflowId', '#F53F3F')
      return
    }
    const success = await confirm(detailId, body)
    if (success) {
      showToast('子阶段已确认，继续执行', '#00B42A')
      refresh()
    } else {
      showToast('确认失败，请重试', '#F53F3F')
    }
  }
  const handleSkip = () => {
    showToast('已跳过该阶段', '#86909C')
    setShowReviewPanel(false)
  }

  /* ── 审核面板按工作流状态自动显隐 ── */
  useEffect(() => {
    if (!workflow) {
      setShowReviewPanel(false)
      return
    }
    if (awaitingPlatformSelection) {
      // 平台选择阶段：审核面板隐藏，由「请选择发布平台」卡片接管
      setShowReviewPanel(false)
    } else if (workflow.status === 'AWAITING_HUMAN') {
      setShowReviewPanel(true)
    } else if (workflow.status === 'COMPLETED' || workflow.status === 'FAILED') {
      setShowReviewPanel(false)
    }
  }, [workflow?.status, awaitingPlatformSelection])

  /* ── 作品下载 / 独立服务（数据分析、优化迭代） ── */
  const handleDownload = async () => {
    if (!workflowId) return
    try {
      setActionLoading('download')
      const blob = await downloadWorkflow(workflowId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `contentops-work-${workflowId.slice(0, 8)}.zip`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      showToast('作品已开始下载', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '下载失败，请稍后重试', '#F53F3F')
    } finally {
      setActionLoading(null)
    }
  }

  const handleRunService = async (service: 'analyze' | 'optimize') => {
    if (!workflowId) return
    try {
      setActionLoading(service)
      if (service === 'analyze') {
        await runStandaloneAnalysis(workflowId)
      } else {
        await runStandaloneOptimize(workflowId)
      }
      showToast(service === 'analyze' ? '数据分析服务已启动' : '优化迭代服务已启动', '#165DFF')
      refresh()
    } catch (err: any) {
      showToast(err?.message || '独立服务启动失败', '#F53F3F')
    } finally {
      setActionLoading(null)
    }
  }

  /* ── 作品聊天续改：打开面板 / 发送消息 / 应用修改 ── */
  const openChatPanel = async () => {
    setChatOpen(true)
    if (!workflowId) return
    try {
      const sessions = await listWorkflowDiscussions(workflowId)
      setChatSessions(sessions)
      if (sessions.length > 0) {
        const first = sessions[0]
        setChatSessionId(first.sessionId)
        setChatMessages(
          (first.turns ?? []).map((t) => ({
            role: t.role === 'assistant' ? 'assistant' : 'user',
            content: t.content,
          }))
        )
      } else {
        setChatSessionId(null)
        setChatMessages([])
      }
    } catch (err: any) {
      showToast(err?.message || '加载聊天记录失败', '#F53F3F')
    }
  }

  const handleSendChat = async () => {
    const text = chatInput.trim()
    if (!workflowId || !text || chatBusy) return
    setChatInput('')
    setChatBusy(true)
    setChatMessages((prev) => [...prev, { role: 'user', content: text }])
    try {
      let sid = chatSessionId
      if (!sid) {
        const started = await startWorkflowDiscussion(workflowId, text)
        sid = started.sessionId
        setChatSessionId(sid)
        setChatMessages((prev) => [...prev, { role: 'assistant', content: started.message }])
        setChatSessions(await listWorkflowDiscussions(workflowId))
      } else {
        const resp = await chatDiscussion(sid, { message: text })
        setChatMessages((prev) => [...prev, { role: 'assistant', content: resp.message }])
      }
    } catch (err: any) {
      showToast(err?.message || '消息发送失败', '#F53F3F')
    } finally {
      setChatBusy(false)
    }
  }

  const handleApplyChat = async () => {
    if (!workflowId || !chatSessionId || chatBusy) return
    setChatBusy(true)
    try {
      await applyDiscussionModification(workflowId, chatSessionId)
      showToast('修改已应用到作品，重新下载 ZIP 即为最新版本', '#00B42A')
      refresh()
    } catch (err: any) {
      showToast(err?.message || '应用修改失败', '#F53F3F')
    } finally {
      setChatBusy(false)
    }
  }

  /* ── 作品合集：打开面板 / 保存归属 ── */
  const openCollectionPanel = async () => {
    setCollectionOpen(true)
    if (!workflowId) return
    setCollectionBusy(true)
    try {
      const [collections, mine] = await Promise.all([
        listCollections(),
        listCollectionsByWorkflow(workflowId),
      ])
      setAllCollections(collections)
      setWorkCollectionIds(mine.map((c) => c.collectionId))
    } catch (err: any) {
      showToast(err?.message || '加载合集失败', '#F53F3F')
    } finally {
      setCollectionBusy(false)
    }
  }

  const toggleCollection = (collectionId: string) => {
    setWorkCollectionIds((prev) =>
      prev.includes(collectionId)
        ? prev.filter((id) => id !== collectionId)
        : [...prev, collectionId]
    )
  }

  const saveCollectionMembership = async () => {
    if (!workflowId || collectionBusy) return
    setCollectionBusy(true)
    try {
      const current = new Set(
        (await listCollectionsByWorkflow(workflowId)).map((c) => c.collectionId)
      )
      const target = new Set(workCollectionIds)
      for (const id of allCollections.map((c) => c.collectionId)) {
        if (target.has(id) && !current.has(id)) {
          await addWorkToCollection(id, workflowId)
        } else if (!target.has(id) && current.has(id)) {
          await removeWorkFromCollection(id, workflowId)
        }
      }
      showToast('合集归属已更新', '#00B42A')
      setCollectionOpen(false)
    } catch (err: any) {
      showToast(err?.message || '保存失败', '#F53F3F')
    } finally {
      setCollectionBusy(false)
    }
  }

  /* ── pipeline helpers ── */
  const connectorType = (prevStatus: StageStatus): ConnectorType => {
    if (prevStatus === 'completed') return 'done'
    if (prevStatus === 'running') return 'active'
    return 'idle'
  }

  /* ── chat message renderer ── */
  const renderMessage = (m: ChatMessage) => {
    if (m.type === 'system') {
      return (
        <div key={m.id} className="text-center">
          <span
            className="inline-block rounded-full px-3 py-1 text-[11px]"
            style={{ color: '#86909C', background: '#F7F8FA' }}
          >
            {m.content}
          </span>
        </div>
      )
    }
    if (m.type === 'agent') {
      return (
        <div key={m.id} className="flex items-start gap-2.5">
          <div
            className="mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-[10px] font-bold text-white"
            style={{ background: 'linear-gradient(135deg, #165DFF, #4080FF)' }}
          >
            C
          </div>
          <div className="max-w-[85%] flex-1">
            <div className="mb-1 flex items-center gap-2">
              <span className="text-xs font-semibold" style={{ color: '#1D2129' }}>
                Content Agent
              </span>
              {m.time && <span className="text-[10px]" style={{ color: '#86909C' }}>{m.time}</span>}
            </div>
            <div
              className="px-4 py-3 text-sm leading-relaxed"
              style={{ background: '#F7F8FA', borderRadius: '12px 12px 12px 4px', color: '#1D2129' }}
            >
              <MarkdownView content={m.content} />
            </div>
            {m.suggestions && m.suggestions.length > 0 && (
              <div
                className="mt-2 p-4"
                style={{ background: '#FFFFFF', border: '1px solid #E5E6EB', borderRadius: '12px' }}
              >
                <p className="mb-3 text-xs font-semibold" style={{ color: '#1D2129' }}>
                  {m.suggestionTitle || '方案推荐'}
                </p>
                <div className="mb-3 space-y-2">
                  {m.suggestions.map((s, idx) => {
                    const letter = String.fromCharCode(65 + idx)
                    return (
                      <div
                        key={idx}
                        className="suggestion-option cursor-pointer rounded-lg px-3 py-2.5 text-sm transition-colors"
                        style={{ border: '1px solid #E5E6EB' }}
                      >
                        <span className="font-medium" style={{ color: '#165DFF' }}>
                          方案 {letter}:
                        </span>
                        <span style={{ color: '#1D2129' }}> {s}</span>
                      </div>
                    )
                  })}
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleSuggestionAction('accept')}
                    className="rounded-lg px-4 py-1.5 text-xs font-medium text-white transition-colors"
                    style={{ background: '#00B42A' }}
                  >
                    采纳
                  </button>
                  <button
                    onClick={() => handleSuggestionAction('reject')}
                    className="rounded-lg px-4 py-1.5 text-xs font-medium transition-colors"
                    style={{ background: '#F2F3F5', color: '#4E5969' }}
                  >
                    拒绝
                  </button>
                  <button
                    onClick={() => handleSuggestionAction('regenerate')}
                    className="rounded-lg border px-4 py-1.5 text-xs font-medium transition-colors"
                    style={{ background: 'transparent', borderColor: '#E5E6EB', color: '#4E5969' }}
                  >
                    重新生成
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )
    }
    // user
    return (
      <div key={m.id} className="flex items-start justify-end gap-2.5">
        <div className="max-w-[85%]">
          <div
            className="px-4 py-3 text-sm leading-relaxed text-white"
            style={{ background: '#165DFF', borderRadius: '12px 12px 4px 12px' }}
          >
            {m.content}
          </div>
          {m.time && (
            <div className="mt-1 text-right">
              <span className="text-[10px]" style={{ color: '#86909C' }}>{m.time}</span>
            </div>
          )}
        </div>
        <div
          className="mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-[10px] font-bold text-white"
          style={{ background: '#FF2D5E' }}
        >
          U
        </div>
      </div>
    )
  }

  /* ── preview modules renderer ── */
  const renderPreviewModules = (large = false) => (
    <>
      <PreviewModule module="title" selected={selectedModule === 'title'} onSelect={setSelectedModule}>
        <div
          className={`px-5 py-6 font-bold transition-colors ${large ? 'text-2xl' : 'text-lg'}`}
          style={{ color: '#1D2129', background: '#FFFFFF' }}
        >
          {workTitle || '作品标题将在此展示'}
        </div>
      </PreviewModule>

      <PreviewModule module="cover" selected={selectedModule === 'cover'} onSelect={setSelectedModule}>
        <div
          className="flex items-center justify-center"
          style={{
            height: large ? 320 : 200,
            background: 'linear-gradient(135deg, rgba(255,45,94,0.12), rgba(22,93,255,0.12))',
            overflow: 'hidden',
          }}
        >
          {coverUrl ? (
            <img
              src={coverUrl}
              alt="封面预览"
              className="h-full w-full object-cover"
            />
          ) : (
            <div className="text-center">
              <IconImage className="mx-auto mb-2 h-10 w-10" style={{ color: '#C9CDD4' }} />
              <span className="text-xs" style={{ color: '#86909C' }}>
                封面图预览区域（AI 生图完成后展示，当前可上传替换）
              </span>
            </div>
          )}
        </div>
      </PreviewModule>

      <PreviewModule module="content" selected={selectedModule === 'content'} onSelect={setSelectedModule}>
        <div
          className={`whitespace-pre-wrap px-5 py-4 leading-[1.8] transition-colors ${large ? 'text-base' : 'text-sm'}`}
          style={{ color: '#4E5969', background: '#FFFFFF' }}
        >
          {workContent ? (
            workContent
          ) : (
            <p className="text-xs" style={{ color: '#C9CDD4' }}>正文内容将在创作完成后展示</p>
          )}
        </div>
      </PreviewModule>

      <PreviewModule module="layout" selected={selectedModule === 'layout'} onSelect={setSelectedModule}>
        <div className="px-5 py-4 transition-colors" style={{ background: '#FFFFFF' }}>
          <p className="mb-2 text-xs font-semibold" style={{ color: '#1D2129' }}>排版效果预览</p>
          <div className="space-y-3">
            <div className="rounded-lg px-3 py-2" style={{ background: '#F7F8FA', borderLeft: '3px solid #165DFF' }}>
              <p className="mb-1 text-xs font-semibold" style={{ color: '#1D2129' }}>
                {workTitle || '作品标题'}
              </p>
              <p className="text-[11px] leading-relaxed" style={{ color: '#86909C' }}>
                最终版式（小红书卡片 / 公众号排版）以下载 ZIP 内的 HTML 为准
              </p>
            </div>
            {coverUrl && (
              <img src={coverUrl} alt="封面" className="w-full rounded-lg" style={{ maxHeight: 120, objectFit: 'cover' }} />
            )}
          </div>
        </div>
      </PreviewModule>

      <PreviewModule module="tags" selected={selectedModule === 'tags'} onSelect={setSelectedModule}>
        <div className="flex flex-wrap gap-2 px-5 py-3 pb-6 transition-colors" style={{ background: '#FFFFFF' }}>
          {PREVIEW_TAGS.map((t) => (
            <span
              key={t}
              className="rounded-full px-3 py-1 text-[11px] font-medium"
              style={{ background: '#FFF0F5', color: '#C40E3A' }}
            >
              {t}
            </span>
          ))}
        </div>
      </PreviewModule>
    </>
  )

  const reviewMins = Math.floor(reviewSeconds / 60)
  const reviewSecs = reviewSeconds % 60

  /* ============================================================
     Render
     ============================================================ */
  if (!workflowId) {
    return (
      <Layout activeNav="dashboard" breadcrumbs={[{ label: '工作流仪表盘', href: '/' }, { label: '工作流详情' }]}>
        <ErrorView message="缺少 workflowId 参数，请从工作流仪表盘或创建工作流页面进入。" />
      </Layout>
    )
  }

  if (loading && !workflow) {
    return (
      <Layout activeNav="dashboard" breadcrumbs={[{ label: '工作流仪表盘', href: '/' }, { label: '工作流详情' }]}>
        <LoadingView text="正在加载工作流状态..." />
      </Layout>
    )
  }

  if (error && !workflow) {
    return (
      <Layout activeNav="dashboard" breadcrumbs={[{ label: '工作流仪表盘', href: '/' }, { label: '工作流详情' }]}>
        <ErrorView message={error} onRetry={refresh} />
      </Layout>
    )
  }

  return (
    <Layout
      activeNav="dashboard"
      breadcrumbs={[{ label: '工作流仪表盘', href: '/' }, { label: workflow?.accountProfile?.accountName || '工作流详情' }]}
      headerRight={
        <div className="flex items-center gap-1.5 text-xs" style={{ color: '#86909C' }}>
          <span
            className="h-1.5 w-1.5 rounded-full animate-pulse-dot"
            style={{ background: workflow?.status === 'COMPLETED' ? '#00B42A' : '#165DFF' }}
          />
          <span>{workflow?.status === 'COMPLETED' ? '已完成' : '实时同步'}</span>
          {workflow && (
            <span style={{ marginLeft: 8, color: '#C9CDD4' }}>|</span>
          )}
          {workflow && (
            <span style={{ marginLeft: 8 }}>
              ID: {workflow.workflowId.substring(0, 8)}...
            </span>
          )}
          <span style={{ marginLeft: 8, color: '#C9CDD4' }}>|</span>
          <button
            onClick={() => materialInputRef.current?.click()}
            disabled={actionLoading === 'material'}
            className="rounded-lg px-3 py-1.5 font-medium transition-colors"
            style={{
              background: '#FFF7E8',
              color: '#B27B16',
              opacity: actionLoading === 'material' ? 0.6 : 1,
              cursor: actionLoading === 'material' ? 'not-allowed' : 'pointer',
            }}
          >
            {actionLoading === 'material' ? '上传中…' : '上传素材'}
          </button>
          <input
            ref={materialInputRef}
            type="file"
            accept=".md,.txt,.pdf,.docx,.doc"
            style={{ display: 'none' }}
            onChange={handleUploadMaterial}
          />
          {workflow?.status === 'COMPLETED' && (
            <>
              <span style={{ marginLeft: 8, color: '#C9CDD4' }}>|</span>
              <button
                onClick={openChatPanel}
                className="rounded-lg px-3 py-1.5 font-medium transition-colors"
                style={{
                  background: '#E8FFEA',
                  color: '#00B42A',
                  cursor: 'pointer',
                }}
              >
                聊天修改
              </button>
              <button
                onClick={openCollectionPanel}
                className="rounded-lg px-3 py-1.5 font-medium transition-colors"
                style={{
                  background: '#E8F3FF',
                  color: '#165DFF',
                  cursor: 'pointer',
                }}
              >
                加入合集
              </button>
              <button
                onClick={handleDownload}
                disabled={actionLoading === 'download'}
                className="rounded-lg px-3 py-1.5 font-medium text-white transition-colors"
                style={{
                  background: '#165DFF',
                  opacity: actionLoading === 'download' ? 0.6 : 1,
                  cursor: actionLoading === 'download' ? 'not-allowed' : 'pointer',
                }}
              >
                {actionLoading === 'download' ? '打包中…' : '下载作品'}
              </button>
              <button
                onClick={() => handleRunService('analyze')}
                disabled={actionLoading !== null}
                className="rounded-lg px-3 py-1.5 font-medium transition-colors"
                style={{
                  background: '#E8F3FF',
                  color: '#165DFF',
                  opacity: actionLoading !== null ? 0.6 : 1,
                  cursor: actionLoading !== null ? 'not-allowed' : 'pointer',
                }}
              >
                数据分析
              </button>
              <button
                onClick={() => handleRunService('optimize')}
                disabled={actionLoading !== null}
                className="rounded-lg px-3 py-1.5 font-medium transition-colors"
                style={{
                  background: '#FFF0F5',
                  color: '#C40E3A',
                  opacity: actionLoading !== null ? 0.6 : 1,
                  cursor: actionLoading !== null ? 'not-allowed' : 'pointer',
                }}
              >
                优化迭代
              </button>
            </>
          )}
        </div>
      }
    >
      <style>{`
        @keyframes dash-flow-bg {
          from { background-position: 0 0; }
          to { background-position: 12px 0; }
        }
        .connector-active-flow {
          background: repeating-linear-gradient(90deg, #165DFF 0, #165DFF 6px, transparent 6px, transparent 12px);
          animation: dash-flow-bg 0.8s linear infinite;
        }
        .preview-module { transition: outline-color 150ms ease, background-color 150ms ease; }
        .preview-module:not(.is-selected):hover {
          outline: 2px dashed #165DFF;
          outline-offset: -2px;
        }
        .suggestion-option:hover {
          border-color: #B5CFFF !important;
          background: #E8F3FF;
        }
        .stage-node-card { transition: border-color 200ms ease, box-shadow 200ms ease; }
        .stage-node-wrap:hover .stage-node-card { border-color: #C9CDD4 !important; }
      `}</style>

      <div className="space-y-6">
        {/* ── 0. PLATFORM SELECTION (topic done) ── */}
        {awaitingPlatformSelection && (
          <section
            className="card animate-fadeInUp p-5"
            style={{ border: '1px solid #B5CFFF', background: '#F7FAFF' }}
          >
            <div className="mb-1 flex items-center gap-2">
              <span
                className="inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-medium"
                style={{ background: '#E8F3FF', color: '#165DFF' }}
              >
                选题已完成
              </span>
              <h3 className="text-sm font-bold" style={{ color: '#1D2129' }}>
                请选择发布平台
              </h3>
            </div>
            <p className="mb-4 text-xs" style={{ color: '#86909C' }}>
              选择一个平台将直接在本工作流继续产出；选择多个平台将并行产出，每个平台一条独立流水线。
            </p>
            <div className="flex flex-wrap gap-2">
              {PLATFORM_OPTIONS.map((p) => {
                const checked = selectedPlatforms.includes(p.code)
                return (
                  <label
                    key={p.code}
                    className="flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-xs font-medium transition-colors"
                    style={{
                      borderColor: checked ? p.color : '#E5E6EB',
                      background: checked ? '#FFFFFF' : '#FFFFFF',
                      color: checked ? p.color : '#4E5969',
                    }}
                  >
                    <input
                      type="checkbox"
                      className="h-3.5 w-3.5 cursor-pointer"
                      style={{ accentColor: p.color }}
                      checked={checked}
                      onChange={() => togglePlatform(p.code)}
                    />
                    {p.name}
                  </label>
                )
              })}
            </div>
            <button
              type="button"
              className="mt-4 inline-flex items-center gap-1.5 rounded-lg border-none px-5 py-2 text-sm font-medium text-white transition-opacity"
              style={{ background: '#165DFF', opacity: selectedPlatforms.length === 0 || submittingSelection ? 0.5 : 1 }}
              disabled={selectedPlatforms.length === 0 || submittingSelection}
              onClick={handleSelectPlatforms}
            >
              {submittingSelection ? '确认中...' : `确认平台并开始产出（${selectedPlatforms.length}）`}
            </button>
          </section>
        )}
        {/* ── 0. PLATFORM BRANCHES ── */}
        {branches.length > 0 && (
          <section className="card animate-fadeInUp p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="text-sm font-semibold" style={{ color: '#1D2129' }}>
                多平台并行产出
              </div>
              <div className="text-xs" style={{ color: '#86909C' }}>
                每个平台一条独立流水线，可分别查看与确认
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              {branches.map((b) => {
                const isActive = detailId === b.workflowId
                const done = b.status === 'COMPLETED'
                const failed = b.status === 'FAILED'
                return (
                  <button
                    key={b.workflowId}
                    type="button"
                    onClick={() => setActiveBranchId(b.workflowId)}
                    className="flex items-center gap-2 rounded-lg border px-3 py-2 text-xs font-medium transition-colors"
                    style={{
                      borderColor: isActive ? '#165DFF' : '#E5E6EB',
                      background: isActive ? '#E8F3FF' : '#FFFFFF',
                      color: isActive ? '#165DFF' : '#4E5969',
                    }}
                  >
                    <span
                      className="h-2 w-2 rounded-full"
                      style={{
                        background: failed
                          ? '#F53F3F'
                          : done
                            ? '#00B42A'
                            : b.status === 'AWAITING_HUMAN'
                              ? '#FF7D00'
                              : '#165DFF',
                      }}
                    />
                    <span>{b.platformName}</span>
                    {b.accountName && (
                      <span className="opacity-70">{b.accountName}</span>
                    )}
                  </button>
                )
              })}
            </div>
          </section>
        )}
        {/* ── 1. WORKFLOW HEADER ── */}
        <section
          className="card animate-fadeInUp p-6"
          style={{ borderBottom: '2px solid #E5E6EB' }}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link
                to="/"
                aria-label="返回仪表盘"
                className="group flex h-10 w-10 items-center justify-center rounded-lg border transition-colors hover:border-[#C9CDD4]"
                style={{ borderColor: '#E5E6EB' }}
              >
                <IconChevronLeft className="h-5 w-5 text-[#86909C] transition-colors group-hover:text-[#4E5969]" />
              </Link>
              <div>
                <div className="mb-1 flex items-center gap-3">
                  <h1 className="text-xl font-bold" style={{ color: '#1D2129' }}>
                    个人成长选题
                  </h1>
                  <span
                    className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium"
                    style={{ background: '#E8F3FF', color: '#165DFF', border: '1px solid #B5CFFF' }}
                  >
                    <span
                      className="h-1.5 w-1.5 rounded-full animate-pulse-dot"
                      style={{ background: '#165DFF' }}
                    />
                    运行中
                  </span>
                </div>
                <div className="flex flex-wrap items-center gap-4 text-xs" style={{ color: '#86909C' }}>
                  <span className="flex items-center gap-1.5">
                    <IconHash className="h-3.5 w-3.5" />
                    ID:
                    <code
                      className="rounded bg-[#F2F3F5] px-1.5 py-0.5 font-mono text-[11px]"
                      style={{ color: '#4E5969' }}
                    >
                      wf-001
                    </code>
                  </span>
                  <span className="flex items-center gap-1.5">
                    <IconClock className="h-3.5 w-3.5" />
                    创建于 2026-07-24 14:30
                  </span>
                  <span className="flex items-center gap-1.5">
                    <IconUser className="h-3.5 w-3.5" />
                    账号: <span className="font-medium" style={{ color: '#4E5969' }}>成长日记</span>
                  </span>
                </div>
              </div>
            </div>
            <button
              onClick={() => setShowError((v) => !v)}
              className="btn-outline flex-shrink-0"
              style={{ padding: '6px 14px', fontSize: 13 }}
            >
              <IconWarnTriangle className="h-3.5 w-3.5" />
              {showError ? '清除异常' : '模拟异常'}
            </button>
          </div>
        </section>

        {/* ── 2. PIPELINE VISUALIZATION ── */}
        <section className="card animate-fadeInUp px-5 py-4" style={{ animationDelay: '80ms' }}>
          <div className="mb-3 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h2 className="text-sm font-bold" style={{ color: '#1D2129' }}>
                Agent 管线
              </h2>
              <div className="flex flex-wrap items-center gap-3 text-[11px]" style={{ color: '#86909C' }}>
                {LEGEND.map((l) => (
                  <span key={l.label} className="flex items-center gap-1">
                    <span
                      className={`h-1.5 w-1.5 rounded-full ${l.pulse ? 'animate-pulse-dot' : ''}`}
                      style={{ background: l.color }}
                    />
                    {l.label}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* Horizontal pipeline */}
          <div
            className="no-scrollbar flex items-start gap-0 overflow-x-auto"
            role="list"
            aria-label="工作流管线阶段"
          >
            {pipelineStages.map((stage, i) => {
              const isCompleted = stage.status === 'completed'
              const isRunning = stage.status === 'running'
              const isSelected = selectedStage === stage.id
              return (
                <div key={stage.id} className="stage-node-wrap flex items-start">
                  <button
                    type="button"
                    className="stage-node flex flex-shrink-0 cursor-pointer flex-col items-center"
                    style={{ background: 'transparent', border: 'none', padding: 0 }}
                    onClick={() => setSelectedStage(isSelected ? null : stage.id)}
                    role="listitem"
                    aria-label={`阶段${i + 1}: ${stage.name} - ${
                      isCompleted ? '已完成' : isRunning ? '运行中' : '等待中'
                    }`}
                  >
                    <div
                      className="stage-node-card flex flex-col items-center rounded-lg border-2"
                      style={{
                        width: isCompleted ? 110 : 140,
                        borderColor: isRunning ? '#165DFF' : '#E5E6EB',
                        background: isRunning ? '#E8F3FF' : '#FFFFFF',
                        padding: isCompleted ? '10px 12px' : '16px',
                        gap: isCompleted ? 6 : 8,
                        boxShadow: isSelected
                          ? '0 0 0 2px #165DFF'
                          : 'none',
                      }}
                    >
                      <div
                        className={`flex items-center justify-center font-bold text-white ${
                          isCompleted ? 'h-6 w-6 rounded-md text-xs' : 'h-8 w-8 rounded-lg text-sm'
                        }`}
                        style={{
                          background: isCompleted
                            ? '#00B42A'
                            : isRunning
                              ? '#165DFF'
                              : '#C9CDD4',
                        }}
                      >
                        {i + 1}
                      </div>
                      <div className="text-center">
                        <div
                          className={isCompleted ? 'text-xs font-medium' : 'text-sm font-medium'}
                          style={{ color: '#1D2129' }}
                        >
                          {stage.name}
                        </div>
                        <div className="text-[11px]" style={{ color: '#86909C' }}>
                          {stage.agent}
                        </div>
                      </div>
                      {isCompleted ? (
                        <div
                          className="flex items-center gap-1 text-[10px] font-medium"
                          style={{ color: '#00B42A' }}
                        >
                          <CheckCircleFilled className="h-3 w-3" />
                          已完成
                        </div>
                      ) : isRunning ? (
                        <div
                          className="flex items-center gap-1 text-[10px] font-medium"
                          style={{ color: '#165DFF' }}
                        >
                          <Spinner size={12} sw={3} />
                          运行中
                        </div>
                      ) : (
                        <div className="text-[10px] font-medium" style={{ color: '#86909C' }}>
                          等待中
                        </div>
                      )}
                      <div
                        className="rounded px-2 py-0.5 font-mono text-[10px]"
                        style={{ background: '#F2F3F5', color: '#86909C' }}
                      >
                        {stage.agent}
                      </div>
                    </div>
                  </button>

                  {i < pipelineStages.length - 1 && (
                    <div
                      className="flex items-center self-center"
                      style={{
                        marginTop: isCompleted ? 16 : 24,
                        height: 2,
                        flex: 1,
                        minWidth: 32,
                        width: 48,
                      }}
                    >
                      <div
                        className={`h-full w-full rounded-full ${
                          connectorType(stage.status) === 'active'
                            ? 'connector-active-flow'
                            : ''
                        }`}
                        style={{
                          background:
                            connectorType(stage.status) === 'done'
                              ? '#00B42A'
                              : connectorType(stage.status) === 'idle'
                                ? '#E5E6EB'
                                : undefined,
                        }}
                      />
                    </div>
                  )}
                </div>
              )
            })}
          </div>

          {/* Pipeline progress */}
          <div className="mt-3 flex items-center gap-2 text-[11px]" style={{ color: '#86909C' }}>
            <span>进度</span>
            <div
              className="h-1 flex-1 overflow-hidden rounded-full"
              style={{ background: '#E5E6EB' }}
            >
              <div
                className="h-full rounded-full"
                style={{
                  background: 'linear-gradient(90deg, #00B42A, #165DFF)',
                  width: '25%',
                  transition: 'width 500ms ease',
                }}
              />
            </div>
            <span className="font-mono font-medium" style={{ color: '#4E5969' }}>
              1/6
            </span>
          </div>
        </section>

        {/* ── 3. CURRENT STAGE DETAIL PANEL ── */}
        <div
          className="grid animate-fadeInUp gap-6 lg:grid-cols-5"
          style={{ animationDelay: '160ms' }}
        >
          {/* LEFT: AI Chat Panel (3 cols) */}
          <div
            className="card flex flex-col overflow-hidden lg:col-span-3"
            style={{ maxHeight: 'calc(100vh - 360px)' }}
          >
            {/* Chat Header */}
            <div
              className="flex flex-shrink-0 items-center justify-between border-b px-5 py-3.5"
              style={{ borderColor: '#E5E6EB', background: '#FFFFFF' }}
            >
              <div className="flex items-center gap-3">
                <div
                  className="flex h-8 w-8 items-center justify-center rounded-full text-xs font-bold text-white"
                  style={{ background: 'linear-gradient(135deg, #165DFF, #4080FF)' }}
                >
                  C
                </div>
                <div>
                  <h3 className="text-sm font-bold" style={{ color: '#1D2129' }}>
                    Content Agent
                  </h3>
                  <p className="text-[11px]" style={{ color: '#86909C' }}>
                    内容创作阶段
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Spinner size={14} sw={2.5} />
                <span className="text-xs font-medium" style={{ color: '#165DFF' }}>
                  运行中
                </span>
                <span
                  className="rounded px-2 py-0.5 font-mono text-[11px]"
                  style={{ background: '#F2F3F5', color: '#86909C' }}
                >
                  :8082
                </span>
              </div>
            </div>

            {/* Chat Messages */}
            <div
              ref={chatScrollRef}
              className="custom-scrollbar flex-1 space-y-4 overflow-y-auto px-5 py-4"
              style={{ background: '#FFFFFF' }}
            >
              {messages.map(renderMessage)}
            </div>

            {/* Chat Input Area */}
            <div
              className="flex flex-shrink-0 items-center gap-2 border-t px-4 py-3"
              style={{ borderColor: '#E5E6EB', background: '#FFFFFF' }}
            >
              <input
                type="text"
                className="flex-1 px-4 py-2.5 text-sm outline-none transition-colors"
                style={{
                  border: '1px solid #E5E6EB',
                  borderRadius: 20,
                  background: inputFocused ? '#FFFFFF' : '#F7F8FA',
                  color: '#1D2129',
                  borderColor: inputFocused ? '#165DFF' : '#E5E6EB',
                  boxShadow: inputFocused ? '0 0 0 2px rgba(22,93,255,0.15)' : 'none',
                }}
                placeholder="输入消息..."
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                onFocus={() => setInputFocused(true)}
                onBlur={() => setInputFocused(false)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleSend()
                }}
              />
              <button
                onClick={handleSend}
                aria-label="发送消息"
                className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full text-white transition-colors"
                style={{ background: '#165DFF' }}
              >
                <IconSend className="h-4 w-4" />
              </button>
            </div>
          </div>

          {/* RIGHT: Work Preview / Edit Panel (2 cols) */}
          <div
            className="card flex flex-col overflow-hidden lg:col-span-2"
            style={{ maxHeight: 'calc(100vh - 360px)' }}
          >
            {/* Preview Header */}
            <div
              className="flex flex-shrink-0 items-center justify-between border-b px-5 py-3.5"
              style={{ borderColor: '#E5E6EB', background: '#FFFFFF' }}
            >
              <div className="flex items-center gap-2">
                <IconEye className="h-4 w-4" style={{ color: '#1D2129' }} />
                <h3 className="text-sm font-bold" style={{ color: '#1D2129' }}>
                  作品预览
                </h3>
                {selectedModule && (
                  <span
                    className="rounded px-2 py-0.5 text-[11px]"
                    style={{ background: '#F2F3F5', color: '#86909C' }}
                  >
                    {MODULE_LABELS[selectedModule]}
                  </span>
                )}
              </div>
              <button
                onClick={() => setFullscreen(true)}
                className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors"
                style={{ color: '#165DFF', background: '#E8F3FF' }}
              >
                <IconMaximize className="h-3.5 w-3.5" />
                进入全屏编辑
              </button>
            </div>

            {/* Preview Content */}
            <div
              className="custom-scrollbar flex-1 overflow-y-auto"
              style={{ background: '#F7F8FA' }}
              onClick={(e) => {
                if (e.currentTarget === e.target) setSelectedModule(null)
              }}
            >
              {renderPreviewModules(false)}
            </div>

            {/* Module Action Bar */}
            {selectedModule && (
              <div
                className="flex flex-shrink-0 flex-col gap-2 border-t px-4 py-3"
                style={{ borderColor: '#E5E6EB', background: '#FFFFFF' }}
              >
                {(selectedModule === 'title' || selectedModule === 'content') && (
                  <div className="w-full">
                    <textarea
                      value={selectedModule === 'title' ? editTitle : editContent}
                      onChange={(e) =>
                        selectedModule === 'title'
                          ? setEditTitle(e.target.value)
                          : setEditContent(e.target.value)
                      }
                      rows={selectedModule === 'title' ? 2 : 8}
                      placeholder={
                        selectedModule === 'title' ? '输入新标题…' : '直接输入修改后的正文…'
                      }
                      className="w-full rounded-lg border px-3 py-2 text-sm outline-none transition-all focus:ring-2 focus:ring-[#165DFF]"
                      style={{
                        borderColor: '#E5E6EB',
                        background: '#F7F8FA',
                        fontFamily: 'inherit',
                        resize: 'vertical',
                      }}
                    />
                    <button
                      onClick={handleSaveContentEdit}
                      disabled={actionLoading === 'edit'}
                      className="mt-2 rounded-lg px-3 py-1.5 text-xs font-medium text-white transition-colors"
                      style={{
                        background: actionLoading === 'edit' ? '#A0CFFF' : '#00B42A',
                        border: 'none',
                        cursor: actionLoading === 'edit' ? 'not-allowed' : 'pointer',
                      }}
                    >
                      {actionLoading === 'edit' ? '保存中…' : '保存修改（确定性生效）'}
                    </button>
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <button
                    onClick={() =>
                      showToast(
                        `正在重新调用 Content Agent 重新编排 ${MODULE_LABELS[selectedModule]}...`,
                        '#165DFF'
                      )
                    }
                    className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors"
                    style={{ background: '#F2F3F5', color: '#4E5969' }}
                  >
                    <IconRefresh className="h-3.5 w-3.5" />
                    重新生成
                  </button>
                  {selectedModule === 'cover' && (
                    <>
                      <button
                        onClick={() => coverInputRef.current?.click()}
                        disabled={actionLoading === 'cover'}
                        className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors"
                        style={{
                          background: '#FFF7E8',
                          color: '#B27B16',
                          opacity: actionLoading === 'cover' ? 0.6 : 1,
                          cursor: actionLoading === 'cover' ? 'not-allowed' : 'pointer',
                        }}
                      >
                        <IconImage className="h-3.5 w-3.5" />
                        {actionLoading === 'cover' ? '上传中…' : '上传封面替换 AI 封面'}
                      </button>
                      <input
                        ref={coverInputRef}
                        type="file"
                        accept=".png,.jpg,.jpeg,.webp,.gif"
                        style={{ display: 'none' }}
                        onChange={handleUploadCover}
                      />
                    </>
                  )}
                  <button
                    onClick={() => {
                      appendMessage({
                        type: 'user',
                        time: nowTime(),
                        content: `我想编辑「${MODULE_LABELS[selectedModule]}」模块的内容`,
                      })
                      window.setTimeout(() => {
                        appendMessage({
                          type: 'agent',
                          time: nowTime(),
                          content: '好的，请告诉我您希望如何调整，我会实时更新预览。',
                        })
                      }, 500)
                    }}
                    className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-white transition-colors"
                    style={{ background: '#165DFF' }}
                  >
                    <IconPen className="h-3.5 w-3.5" />
                    向 AI 提问
                  </button>
                  <button
                    onClick={() => setSelectedModule(null)}
                    className="flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors"
                    style={{ background: 'transparent', borderColor: '#E5E6EB', color: '#4E5969' }}
                  >
                    <IconRestore className="h-3.5 w-3.5" />
                    恢复原版
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* ── 3.5 实时产出（流水线过程流式展示） ── */}
        {(liveStage || liveOutput) && (
          <section className="card animate-fadeInUp p-5">
            <div className="mb-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span
                  className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-medium"
                  style={{ background: '#E8F7FF', color: '#0FC6C2' }}
                >
                  <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full" style={{ background: '#0FC6C2' }} />
                  实时产出
                </span>
                <span className="text-sm font-semibold" style={{ color: '#1D2129' }}>
                  {PIPELINE_STAGES.find((s) => s.id === liveStage)?.name ?? liveStage ?? '流水线'}
                </span>
              </div>
              {liveOutput && (
                <span className="text-[11px]" style={{ color: '#C9CDD4' }}>
                  {liveOutput.length} 字符
                </span>
              )}
            </div>
            {liveTools.length > 0 && (
              <div className="mb-2 flex flex-wrap gap-1.5">
                {liveTools.map((t, i) => (
                  <span
                    key={i}
                    className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-[11px] font-medium"
                    style={{ background: '#FFF0F5', color: '#C40E3A' }}
                  >
                    🔧 {t.tool}
                    <span className="text-[10px]" style={{ color: '#C9CDD4' }}>{t.time}</span>
                  </span>
                ))}
              </div>
            )}
            <div
              className="max-h-56 overflow-y-auto whitespace-pre-wrap rounded-lg px-4 py-3 text-sm leading-relaxed"
              style={{ background: '#F7F8FA', color: '#4E5969' }}
            >
              {liveOutput ? (
                <MarkdownView content={liveOutput} />
              ) : (
                '阶段执行中，产出将在这里流式显示…'
              )}
            </div>
          </section>
        )}

        {/* ── 4. HUMAN REVIEW PANEL ── */}
        {showReviewPanel ? (
          <section
            className="animate-fadeInUp"
            role="dialog"
            aria-label="人工审核面板"
            aria-modal="true"
            style={{
              animationDelay: '240ms',
              background: '#E8F3FF',
              border: '1px solid #B5CFFF',
              borderRadius: 12,
              padding: 24,
              overflow: 'hidden',
            }}
          >
            {/* Stage Context Header */}
            <div className="mb-5 flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div
                  className="flex h-10 w-10 items-center justify-center rounded-lg"
                  style={{ background: '#FFF7E8', border: '1px solid #FFCF8B' }}
                >
                  <IconWarn className="h-5 w-5" style={{ color: '#FF7D00' }} />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-base font-bold" style={{ color: '#1D2129' }}>
                      {reviewStageName} - {reviewStageAgent}
                    </h3>
                    <span
                      className="inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-medium"
                      style={{ background: '#FFF7E8', color: '#FF7D00', border: '1px solid #FFCF8B' }}
                    >
                      <WarnFilled className="h-3 w-3" />
                      {awaitingSubStage ? `子阶段确认 · ${workflow?.currentSubStage}` : '需要你的确认'}
                    </span>
                  </div>
                  <div className="mt-1 flex items-center gap-3 text-xs" style={{ color: '#86909C' }}>
                    <span>Topic Agent :8081</span>
                    <span className="flex items-center gap-1">
                      <IconClock className="h-3 w-3" />
                      等待确认 {reviewMins} 分 {reviewSecs < 10 ? '0' : ''}{reviewSecs} 秒
                    </span>
                  </div>
                </div>
              </div>
              <button
                onClick={() => setShowReviewPanel(false)}
                aria-label="关闭审核面板"
                className="flex h-8 w-8 items-center justify-center rounded-lg transition-colors hover:bg-[#FFFFFF]"
                style={{ color: '#86909C' }}
              >
                <IconClose className="h-4 w-4" />
              </button>
            </div>

            {/* ── 阶段决策卡（大纲确认 / 风格选择 / 发布校验） ── */}
            {decisionKind !== 'topic' && (
              <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
                <div className="lg:col-span-2">
                  <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4E5969' }}>
                    Agent 输出预览
                  </h4>
                  <div className="card p-5" style={{ background: '#FFFFFF' }}>
                    {decisionKind === 'outline' && (
                      <div className="space-y-3">
                        <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
                          {String(outlineArtifact?.title ?? '内容大纲')}
                        </div>
                        {outlineSections.length > 0 ? (
                          <ol className="list-decimal space-y-2 pl-5">
                            {outlineSections.map((s, i) => (
                              <li key={i} className="text-sm" style={{ color: '#4E5969' }}>
                                <span className="font-medium" style={{ color: '#1D2129' }}>{s.heading ?? '未命名段落'}</span>
                                {s.keyPoints && <span className="mt-0.5 block text-xs" style={{ color: '#86909C' }}>{s.keyPoints}</span>}
                              </li>
                            ))}
                          </ol>
                        ) : (
                          <pre className="max-h-64 overflow-auto whitespace-pre-wrap text-xs" style={{ color: '#4E5969' }}>
                            {JSON.stringify(outlineArtifact ?? {}, null, 2)}
                          </pre>
                        )}
                      </div>
                    )}
                    {decisionKind === 'styles' && (
                      <div className="space-y-2">
                        <p className="text-xs" style={{ color: '#86909C' }}>
                          请选择配图风格方向，确认后批量生成配图与封面：
                        </p>
                        {styleDirections.length > 0 ? (
                          styleDirections.map((d, i) => {
                            const active = selectedStyle === d.name
                            return (
                              <button
                                key={i}
                                onClick={() => setSelectedStyle(d.name ?? null)}
                                className="w-full rounded-lg border p-3 text-left transition-all"
                                style={{
                                  borderColor: active ? '#FF2D5E' : '#E5E6EB',
                                  background: active ? 'rgba(255,45,94,0.04)' : '#fff',
                                }}
                              >
                                <span className="text-sm font-medium" style={{ color: active ? '#FF2D5E' : '#1D2129' }}>
                                  风格 {i + 1}：{d.name ?? '未命名'}
                                </span>
                                {d.description && (
                                  <span className="mt-0.5 block text-xs" style={{ color: '#86909C' }}>{d.description}</span>
                                )}
                              </button>
                            )
                          })
                        ) : (
                          <pre className="max-h-64 overflow-auto whitespace-pre-wrap text-xs" style={{ color: '#4E5969' }}>
                            {JSON.stringify(styleArtifact ?? {}, null, 2)}
                          </pre>
                        )}
                      </div>
                    )}
                    {decisionKind === 'publish' && (
                      <div className="space-y-3">
                        <div className="text-base font-bold" style={{ color: '#1D2129' }}>{workTitle || '作品标题'}</div>
                        {coverUrl && <img src={coverUrl} alt="封面" className="w-full rounded-lg" style={{ maxHeight: 240, objectFit: 'cover' }} />}
                        <div className="max-h-72 overflow-auto rounded-lg px-4 py-3 text-sm leading-relaxed" style={{ background: '#F7F8FA', color: '#4E5969' }}>
                          {workContent ? (
                            <MarkdownView content={workContent} />
                          ) : (
                            '正文内容已生成，可在下方下载 ZIP 查看完整排版'
                          )}
                        </div>
                        {publishQuality && (
                          <div className="flex flex-wrap items-center gap-3 text-xs">
                            <span className="rounded px-2 py-0.5 font-medium" style={{ background: '#E8FFEA', color: '#00B42A' }}>
                              质量评分：{String(publishQuality.score ?? publishQuality.overall ?? '—')}
                            </span>
                            {publishQuality.readability != null && <span style={{ color: '#86909C' }}>可读性 {String(publishQuality.readability)}</span>}
                            {publishQuality.logic != null && <span style={{ color: '#86909C' }}>逻辑 {String(publishQuality.logic)}</span>}
                            {publishQuality.originality != null && <span style={{ color: '#86909C' }}>原创性 {String(publishQuality.originality)}</span>}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>
                <div className="space-y-5 lg:col-span-1">
                  {decisionKind === 'publish' && publishChecklist.length > 0 && (
                    <div>
                      <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4E5969' }}>
                        校验清单（Validator）
                      </h4>
                      <div className="card space-y-1 p-4" style={{ background: '#FFFFFF' }}>
                        {publishChecklist.map((item, i) => (
                          <label key={i} className="flex cursor-pointer items-center gap-2 py-1 text-sm" style={{ color: '#4E5969' }}>
                            <input type="checkbox" defaultChecked className="h-4 w-4 cursor-pointer" style={{ accentColor: '#00B42A' }} />
                            <span>{item}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  )}
                  <div>
                    <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4E5969' }}>
                      审核操作
                    </h4>
                    <div className="space-y-3">
                      {decisionKind === 'outline' && (
                        <button
                          onClick={() => handleConfirmSubStage()}
                          className="flex w-full items-center justify-center gap-2 rounded-lg border-none text-sm font-medium text-white"
                          style={{ background: '#165DFF', padding: '8px 24px', cursor: 'pointer' }}
                        >
                          确认大纲并继续
                        </button>
                      )}
                      {decisionKind === 'styles' && (
                        <button
                          onClick={() => handleConfirmSubStage({ confirmedStyle: selectedStyle })}
                          className="flex w-full items-center justify-center gap-2 rounded-lg border-none text-sm font-medium text-white"
                          style={{ background: '#FF2D5E', padding: '8px 24px', cursor: 'pointer' }}
                        >
                          确认风格并生成图片
                        </button>
                      )}
                      {decisionKind === 'publish' && (
                        <button
                          onClick={handleDownload}
                          className="flex w-full items-center justify-center gap-2 rounded-lg border-none text-sm font-medium text-white"
                          style={{ background: '#00B42A', padding: '8px 24px', cursor: 'pointer' }}
                        >
                          下载作品 ZIP
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Two columns: Agent Output Preview + Review Checklist */}
            {decisionKind === 'topic' && (
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              {/* LEFT: Agent Output Preview */}
              <div className="lg:col-span-2">
                <h4
                  className="mb-3 text-xs font-semibold uppercase tracking-wider"
                  style={{ color: '#4E5969' }}
                >
                  Agent 输出预览
                </h4>
                <div className="card p-5" style={{ background: '#FFFFFF' }}>
                  {!awaitingSubStage ? (
                    <div className="space-y-3">
                    <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
                       选题建议 ({reviewTopics.length}个)
                    </div>
                    <div className="space-y-2">
                      {reviewTopics.map((t, i) => (
                        <div
                          key={i}
                          onClick={() => {
                            setSelectedTopicIndex(selectedTopicIndex === i ? null : i)
                            setCustomTopic('')
                          }}
                          className="flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-all"
                          style={{
                            borderColor: selectedTopicIndex === i ? '#FF2D5E' : '#E5E6EB',
                            background: selectedTopicIndex === i ? 'rgba(255,45,94,0.04)' : '#fff',
                          }}
                        >
                          <span
                            className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-xs font-bold text-white"
                            style={{ background: '#FF2D5E' }}
                          >
                            {i + 1}
                          </span>
                          <div>
                            <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
                              {t.title}
                            </div>
                            <div className="mt-1.5 flex flex-wrap gap-1.5">
                              {t.tags.map((tag) => (
                                <span
                                  key={tag}
                                  className="rounded px-2 py-0.5 text-[10px] font-medium"
                                  style={{ background: '#FFF0F5', color: '#C40E3A' }}
                                >
                                  {tag}
                                </span>
                              ))}
                            </div>
                          </div>
                        </div>
                      ))}
                      <input
                        value={customTopic}
                        onChange={(e) => {
                          setCustomTopic(e.target.value)
                          setSelectedTopicIndex(null)
                        }}
                        placeholder="或输入自定义选题（选择其他）…"
                        className="w-full rounded-lg border px-3 py-2 text-sm outline-none transition-all focus:ring-2 focus:ring-[#FF2D5E]"
                        style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
                      />
                    </div>
                    <div className="text-xs" style={{ color: '#86909C' }}>
                      竞品分析: {competitiveAnalysis || '暂无竞品分析数据'}
                    </div>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
                        {reviewStageName} · 子阶段「{workflow?.currentSubStage}」产出已生成
                      </div>
                      <p className="text-sm leading-relaxed" style={{ color: '#4E5969' }}>
                        {workflow?.currentSubStage === 'outline' &&
                          '内容大纲已生成，确认后将进入初稿创作；可在右侧修改意见中提出调整方向。'}
                        {workflow?.currentSubStage === 'styles' &&
                          '配图风格方案已生成，确认后将开始批量生成图片；可在右侧修改意见中调整风格方向。'}
                        {workflow?.currentSubStage !== 'outline' && workflow?.currentSubStage !== 'styles' &&
                          '子阶段产出已就绪，确认后继续下一环节。'}
                      </p>
                    </div>
                  )}
                </div>
              </div>

              {/* RIGHT: Review Checklist + Actions */}
              <div className="space-y-5 lg:col-span-1">
                {/* Review Checklist */}
                <div>
                  <h4
                    className="mb-3 text-xs font-semibold uppercase tracking-wider"
                    style={{ color: '#4E5969' }}
                  >
                    审核检查项
                  </h4>
                  <div className="card space-y-1 p-4" style={{ background: '#FFFFFF' }}>
                    {reviewChecklist.map((item, i) => (
                      <label
                        key={i}
                        className="flex cursor-pointer items-center gap-2 py-1 text-sm"
                        style={{ color: '#4E5969' }}
                      >
                        <input
                          type="checkbox"
                          checked={checklist[i]}
                          onChange={() => toggleChecklist(i)}
                          className="h-4 w-4 cursor-pointer"
                          style={{ accentColor: '#165DFF' }}
                        />
                        <span>{item}</span>
                      </label>
                    ))}
                  </div>
                </div>

                {/* Review Actions */}
                <div>
                  <h4
                    className="mb-3 text-xs font-semibold uppercase tracking-wider"
                    style={{ color: '#4E5969' }}
                  >
                    审核操作
                  </h4>
                  <div className="space-y-3">
                    <button
                      onClick={awaitingSubStage ? () => handleConfirmSubStage() : handleApprove}
                      className="flex w-full cursor-pointer items-center justify-center gap-2 rounded-lg border-none text-sm font-medium text-white transition-all"
                      style={{ background: '#00B42A', padding: '8px 24px' }}
                    >
                      <IconCheck className="h-4 w-4" />
                      {awaitingSubStage ? '确认并继续' : '通过'}
                    </button>
                    {!awaitingSubStage && (
                      <button
                        onClick={() => setShowFeedbackArea((v) => !v)}
                        className="flex w-full cursor-pointer items-center justify-center gap-2 rounded-lg text-sm font-medium transition-all"
                        style={{
                          border: '1px solid #E5E6EB',
                          color: '#4E5969',
                          padding: '8px 24px',
                          background: '#fff',
                        }}
                      >
                        <IconPen className="h-4 w-4" />
                        修改意见
                      </button>
                    )}
                    <button
                      onClick={handleSkip}
                      className="flex w-full cursor-pointer items-center justify-center gap-2 border-none bg-transparent text-xs font-medium transition-colors"
                      style={{ color: '#86909C' }}
                    >
                      跳过此阶段
                    </button>
                  </div>
                </div>
              </div>
            </div>

            )}

            {/* Feedback Textarea (expandable) */}
            {showFeedbackArea && (
              <div className="mt-5">
                <div className="card p-5" style={{ background: '#FFFFFF' }}>
                  <label className="mb-2 block text-xs font-semibold" style={{ color: '#4E5969' }}>
                    修改意见
                  </label>
                  <textarea
                    placeholder="请输入修改意见，Agent 将根据反馈重新执行..."
                    rows={3}
                    maxLength={500}
                    value={feedbackText}
                    onChange={(e) => setFeedbackText(e.target.value)}
                    className="w-full resize-none rounded-lg border px-4 py-3 text-sm outline-none transition-all focus:border-transparent focus:ring-2 focus:ring-[#165DFF]"
                    style={{ background: '#F7F8FA', borderColor: '#E5E6EB', color: '#1D2129' }}
                  />
                  <div className="mt-2 flex items-center justify-between">
                    <span className="text-[11px]" style={{ color: '#86909C' }}>
                      {feedbackText.length} / 500
                    </span>
                    <button
                      onClick={handleSubmitFeedback}
                      className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg border-none px-4 py-2 text-xs font-medium text-white"
                      style={{ background: '#165DFF' }}
                    >
                      <IconSend className="h-3.5 w-3.5" />
                      提交修改意见
                    </button>
                  </div>
                </div>
              </div>
            )}
          </section>
        ) : (
          <section className="card flex items-center justify-between p-4">
            <div className="flex items-center gap-2 text-sm" style={{ color: '#4E5969' }}>
              <IconCheck className="h-4 w-4" style={{ color: '#00B42A' }} />
              选题策划审核已处理
            </div>
            <button
              onClick={() => setShowReviewPanel(true)}
              className="text-sm font-medium"
              style={{ color: '#165DFF' }}
            >
              重新打开审核面板
            </button>
          </section>
        )}

        {/* ── 5. ERROR STATE ── */}
        {showError && (
          <section
            className="card flex flex-col items-center justify-center p-12 text-center"
            role="alert"
          >
            <IconPhoneOff className="mb-4 h-16 w-16" style={{ color: '#C9CDD4' }} />
            <h3 className="mb-2 text-base" style={{ color: '#1D2129' }}>
              网络开小差了
            </h3>
            <p className="mb-4 text-sm" style={{ color: '#86909C' }}>
              <span className="font-medium tabular-nums" style={{ color: '#F53F3F' }}>
                {retryCountdown}
              </span>
              秒后自动重试，或点击下方按钮手动重试
            </p>
            <button
              onClick={() => setShowError(false)}
              className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg border-none px-4 py-2 text-sm font-medium text-white"
              style={{ background: '#165DFF' }}
            >
              <Spinner size={16} sw={2} />
              手动重试
            </button>
          </section>
        )}
      </div>

      {/* ── Fullscreen preview overlay ── */}
      {fullscreen && (
        <div
          className="fixed inset-0 z-50 flex flex-col"
          style={{ background: '#fff' }}
        >
          <div
            className="flex h-14 items-center justify-between border-b px-6"
            style={{ borderColor: '#E5E6EB' }}
          >
            <span className="font-semibold" style={{ color: '#1D2129' }}>
              作品预览 · 全屏编辑
            </span>
            <div className="flex items-center gap-3">
              <Link
                to="/work-detail"
                className="text-xs font-medium"
                style={{ color: '#165DFF' }}
              >
                查看作品详情 →
              </Link>
              <button
                onClick={() => setFullscreen(false)}
                className="btn-outline"
                style={{ padding: '6px 16px' }}
              >
                <IconClose className="h-4 w-4" />
                退出全屏
              </button>
            </div>
          </div>
          <div className="custom-scrollbar flex-1 overflow-y-auto p-8" style={{ background: '#F7F8FA' }}>
            <div className="mx-auto max-w-2xl">{renderPreviewModules(true)}</div>
          </div>
        </div>
      )}

      {/* ── Toast notification ── */}
      {toast && (
        <div
          className="fixed right-6 top-6 z-[100] flex items-center gap-2 rounded-lg border px-4 py-3"
          style={{
            background: '#fff',
            borderColor: toast.color,
            boxShadow: '0 4px 8px -1px rgba(0,0,0,0.05)',
          }}
          role="alert"
        >
          <span
            className="flex h-4 w-4 items-center justify-center rounded-full"
            style={{ background: toast.color, color: '#fff' }}
          >
            <IconCheck className="h-3 w-3" style={{ strokeWidth: 3 }} />
          </span>
          <span className="text-sm" style={{ color: '#1D2129' }}>
            {toast.msg}
          </span>
        </div>
      )}

      {/* ── 作品聊天续改面板：查看聊天记录 + 发送修改意见 + 应用到作品 ── */}
      {chatOpen && (
        <div
          className="fixed bottom-4 right-4 top-20 z-40 flex w-[380px] flex-col overflow-hidden rounded-2xl border shadow-2xl"
          style={{ background: '#fff', borderColor: '#E5E6EB' }}
        >
          <div
            className="flex items-center justify-between border-b px-4 py-3"
            style={{ borderColor: '#E5E6EB' }}
          >
            <span className="text-sm font-semibold" style={{ color: '#1D2129' }}>
              聊天修改作品
            </span>
            <button
              onClick={() => setChatOpen(false)}
              className="flex h-7 w-7 items-center justify-center rounded-lg transition-colors hover:bg-[#F2F3F5]"
              style={{ color: '#86909C' }}
              aria-label="关闭聊天面板"
            >
              <IconClose className="h-4 w-4" />
            </button>
          </div>

          {chatSessions.length > 0 && (
            <div
              className="flex gap-2 overflow-x-auto border-b px-4 py-2"
              style={{ borderColor: '#E5E6EB' }}
            >
              {chatSessions.map((s) => (
                <button
                  key={s.sessionId}
                  onClick={() => {
                    setChatSessionId(s.sessionId)
                    setChatMessages(
                      (s.turns ?? []).map((t) => ({
                        role: t.role === 'assistant' ? 'assistant' : 'user',
                        content: t.content,
                      }))
                    )
                  }}
                  className="rounded-full px-3 py-1 text-xs font-medium transition-colors"
                  style={{
                    background: chatSessionId === s.sessionId ? '#E8F3FF' : '#F7F8FA',
                    color: chatSessionId === s.sessionId ? '#165DFF' : '#4E5969',
                  }}
                >
                  {s.sessionId.slice(0, 8)}
                </button>
              ))}
            </div>
          )}

          <div className="flex-1 space-y-3 overflow-y-auto p-4">
            {chatMessages.length === 0 && (
              <div className="pt-8 text-center text-xs" style={{ color: '#C9CDD4' }}>
                还没有聊天记录，输入你的修改意见开始吧
              </div>
            )}
            {chatMessages.map((m, i) => (
              <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div
                  className="max-w-[80%] rounded-xl px-3 py-2 text-sm"
                  style={{
                    background: m.role === 'user' ? '#165DFF' : '#F2F3F5',
                    color: m.role === 'user' ? '#fff' : '#1D2129',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {m.content}
                </div>
              </div>
            ))}
            {chatBusy && (
              <div className="text-xs" style={{ color: '#86909C' }}>
                AI 思考中…
              </div>
            )}
          </div>

          <div className="border-t p-3" style={{ borderColor: '#E5E6EB' }}>
            <div className="flex items-center gap-2">
              <input
                value={chatInput}
                onChange={(e) => setChatInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSendChat()}
                placeholder="输入修改意见，如：把开头改得更口语化…"
                className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none transition-all focus:ring-2 focus:ring-[#165DFF]"
                style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
              />
              <button
                onClick={handleSendChat}
                disabled={chatBusy || !chatInput.trim()}
                className="rounded-lg px-3 py-2"
                style={{
                  background: chatBusy || !chatInput.trim() ? '#A0CFFF' : '#165DFF',
                  cursor: chatBusy || !chatInput.trim() ? 'not-allowed' : 'pointer',
                }}
                aria-label="发送消息"
              >
                <IconSend className="h-4 w-4" style={{ color: '#fff' }} />
              </button>
            </div>
            <button
              onClick={handleApplyChat}
              disabled={!chatSessionId || chatBusy}
              className="mt-2 w-full rounded-lg py-2 text-sm font-medium text-white transition-colors"
              style={{
                background: !chatSessionId || chatBusy ? '#C9CDD4' : '#00B42A',
                cursor: !chatSessionId || chatBusy ? 'not-allowed' : 'pointer',
              }}
            >
              应用修改到作品
            </button>
          </div>
        </div>
      )}

      {/* ── 加入作品合集弹窗：生成后可把作品归入按类型区分的合集 ── */}
      {collectionOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ background: 'rgba(0,0,0,0.35)' }}
          onClick={() => setCollectionOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-base font-bold" style={{ color: '#1D2129' }}>
                加入作品合集
              </h3>
              <button
                onClick={() => setCollectionOpen(false)}
                className="flex h-7 w-7 items-center justify-center rounded-lg hover:bg-[#F2F3F5]"
                style={{ color: '#86909C' }}
                aria-label="关闭"
              >
                <IconClose className="h-4 w-4" />
              </button>
            </div>

            {collectionBusy ? (
              <div className="py-10 text-center text-sm" style={{ color: '#86909C' }}>
                加载中…
              </div>
            ) : allCollections.length === 0 ? (
              <div className="py-8 text-center">
                <p className="text-sm" style={{ color: '#86909C' }}>还没有作品合集</p>
                <p className="mt-1 text-xs" style={{ color: '#C9CDD4' }}>
                  先到「作品合集」页面创建合集（按类型区分），再来把作品归入
                </p>
              </div>
            ) : (
              <div className="max-h-72 space-y-2 overflow-y-auto">
                {allCollections.map((c) => {
                  const checked = workCollectionIds.includes(c.collectionId)
                  return (
                    <button
                      key={c.collectionId}
                      onClick={() => toggleCollection(c.collectionId)}
                      className="flex w-full items-center justify-between rounded-lg border px-4 py-3 transition-all"
                      style={{
                        borderColor: checked ? '#165DFF' : '#E5E6EB',
                        background: checked ? 'rgba(22,93,255,0.05)' : '#fff',
                        cursor: 'pointer',
                      }}
                    >
                      <span className="text-sm font-medium" style={{ color: '#1D2129' }}>
                        {c.name}
                        <span className="ml-2 text-xs font-normal" style={{ color: '#C40E3A' }}>
                          {c.type}
                        </span>
                      </span>
                      <span
                        className="flex h-5 w-5 items-center justify-center rounded-md border"
                        style={{
                          borderColor: checked ? '#165DFF' : '#C9CDD4',
                          background: checked ? '#165DFF' : '#fff',
                        }}
                      >
                        {checked && <IconCheck className="h-3 w-3" style={{ color: '#fff', strokeWidth: 3 }} />}
                      </span>
                    </button>
                  )
                })}
              </div>
            )}

            <div className="mt-5 flex justify-end gap-2">
              <button
                onClick={() => setCollectionOpen(false)}
                className="rounded-lg px-4 py-2 text-sm font-medium"
                style={{ background: '#F2F3F5', color: '#4E5969', border: 'none', cursor: 'pointer' }}
              >
                取消
              </button>
              <button
                onClick={saveCollectionMembership}
                disabled={collectionBusy || allCollections.length === 0}
                className="rounded-lg px-4 py-2 text-sm font-medium text-white"
                style={{
                  background: collectionBusy || allCollections.length === 0 ? '#C9CDD4' : '#165DFF',
                  border: 'none',
                  cursor: collectionBusy || allCollections.length === 0 ? 'not-allowed' : 'pointer',
                }}
              >
                保存
              </button>
            </div>
          </div>
        </div>
      )}
    </Layout>
  )
}
