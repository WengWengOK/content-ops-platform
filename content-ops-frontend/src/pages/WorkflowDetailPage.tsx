import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'

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
  port: string
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
  { id: 'topic-planning', name: '选题策划', agent: 'Topic Agent', port: ':8081', status: 'completed' },
  { id: 'content-creation', name: '内容创作', agent: 'Content Agent', port: ':8082', status: 'running' },
  { id: 'image-design', name: '配图设计', agent: 'Image Agent', port: ':8083', status: 'pending' },
  { id: 'publishing', name: '排版发布', agent: 'Publish Agent', port: ':8084', status: 'pending' },
  { id: 'data-analysis', name: '数据分析', agent: 'Analysis Agent', port: ':8085', status: 'pending' },
  { id: 'optimization', name: '优化迭代', agent: 'Optimize Agent', port: ':8086', status: 'pending' },
]

const LEGEND: { label: string; color: string; pulse?: boolean }[] = [
  { label: '已完成', color: '#00B42A' },
  { label: '运行中', color: '#165DFF', pulse: true },
  { label: '待审核', color: '#FF7D00' },
  { label: '等待中', color: '#C9CDD4' },
]

const REVIEW_TOPICS: ReviewTopic[] = [
  { title: '30 岁前必须学会的 5 个自律习惯', tags: ['自律', '个人成长', '习惯养成'] },
  { title: '早起改变人生: 我的 100 天打卡记录', tags: ['早起打卡', '100天挑战'] },
  { title: '从社恐到自信: 一个内向者的成长指南', tags: ['内向者', '自信培养'] },
]

const REVIEW_CHECKLIST: string[] = [
  '选题数量(3-5个)',
  '关键词标签',
  '平台适配标题',
  '竞品分析摘要',
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
  const [messages, setMessages] = useState<ChatMessage[]>([
    { id: 1, type: 'system', content: '14:32:01 - 选题策划阶段已完成，进入内容创作阶段' },
    {
      id: 2,
      type: 'agent',
      time: '14:32:08',
      content:
        '我已根据选题"30岁前必须学会的5个自律习惯"生成了内容大纲，包含5个核心要点和3个科学实验数据支撑。请查看右侧预览。',
    },
    {
      id: 3,
      type: 'agent',
      time: '14:32:15',
      content:
        '建议将标题优化为"30岁前必须学会的5个自律习惯（建议收藏）"，预计点击率提升23%。你认为这个标题如何？',
      suggestionTitle: '标题方案推荐',
      suggestions: [
        '30岁前必须学会的5个自律习惯（建议收藏）',
        '自律改变人生：5个让你脱胎换骨的习惯',
        '从社恐到自信：30岁前必须掌握的自律力',
      ],
    },
    {
      id: 4,
      type: 'user',
      time: '14:33:02',
      content: '标题方案 A 不错，但"建议收藏"太常见了，换一个更自然的后缀',
    },
    {
      id: 5,
      type: 'agent',
      time: '14:33:10',
      content:
        '好的，已将标题调整为"30岁前必须学会的5个自律习惯｜真实经历分享"。你可以在右侧预览中看到更新后的效果。',
    },
  ])
  const [inputValue, setInputValue] = useState('')
  const [inputFocused, setInputFocused] = useState(false)
  const [selectedModule, setSelectedModule] = useState<string | null>(null)
  const [showReviewPanel, setShowReviewPanel] = useState(true)
  const [showFeedbackArea, setShowFeedbackArea] = useState(false)
  const [feedbackText, setFeedbackText] = useState('')
  const [checklist, setChecklist] = useState<boolean[]>(
    REVIEW_CHECKLIST.map(() => false),
  )
  const [showError, setShowError] = useState(false)
  const [retryCountdown, setRetryCountdown] = useState(3)
  const [fullscreen, setFullscreen] = useState(false)
  const [selectedStage, setSelectedStage] = useState<string | null>(null)
  const [reviewSeconds, setReviewSeconds] = useState(32)
  const [toast, setToast] = useState<{ msg: string; color: string } | null>(null)

  const chatScrollRef = useRef<HTMLDivElement>(null)

  /* ── chat helpers ── */
  const nowTime = () => {
    const n = new Date()
    const p = (x: number) => x.toString().padStart(2, '0')
    return `${p(n.getHours())}:${p(n.getMinutes())}:${p(n.getSeconds())}`
  }
  const appendMessage = (m: Omit<ChatMessage, 'id'>) => {
    setMessages((prev) => {
      const nextId = prev.length ? Math.max(...prev.map((x) => x.id)) + 1 : 1
      return [...prev, { id: nextId, ...m }]
    })
  }
  const showToast = (msg: string, color = '#00B42A') => {
    setToast({ msg, color })
    window.setTimeout(() => setToast(null), 3000)
  }
  const handleSend = () => {
    const text = inputValue.trim()
    if (!text) return
    appendMessage({ type: 'user', time: nowTime(), content: text })
    setInputValue('')
    window.setTimeout(() => {
      appendMessage({ type: 'agent', time: nowTime(), content: '收到你的反馈，我正在处理中...' })
    }, 600)
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
  const handleApprove = () => {
    showToast('阶段已通过，管线继续执行', '#00B42A')
    setShowReviewPanel(false)
  }
  const handleSubmitFeedback = () => {
    if (!feedbackText.trim()) {
      showToast('请输入修改意见', '#FF7D00')
      return
    }
    showToast('修改意见已提交，Agent 将重新执行', '#165DFF')
    setFeedbackText('')
    setShowFeedbackArea(false)
  }
  const handleSkip = () => {
    showToast('已跳过该阶段', '#86909C')
    setShowReviewPanel(false)
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
              {m.content}
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
          30岁前必须学会的5个自律习惯｜真实经历分享
        </div>
      </PreviewModule>

      <PreviewModule module="cover" selected={selectedModule === 'cover'} onSelect={setSelectedModule}>
        <div
          className="flex items-center justify-center"
          style={{
            height: large ? 320 : 200,
            background: 'linear-gradient(135deg, rgba(255,45,94,0.12), rgba(22,93,255,0.12))',
          }}
        >
          <div className="text-center">
            <IconImage className="mx-auto mb-2 h-10 w-10" style={{ color: '#C9CDD4' }} />
            <span className="text-xs" style={{ color: '#86909C' }}>封面图预览区域</span>
          </div>
        </div>
      </PreviewModule>

      <PreviewModule module="content" selected={selectedModule === 'content'} onSelect={setSelectedModule}>
        <div
          className={`px-5 py-4 leading-[1.8] transition-colors ${large ? 'text-base' : 'text-sm'}`}
          style={{ color: '#4E5969', background: '#FFFFFF' }}
        >
          <p className="mb-3">在快节奏的现代生活中，自律往往被误解为苦行僧式的生活方式。然而，科学研究表明，真正有效的自律习惯并不需要极端的意志力，而是建立在科学方法之上的。</p>
          <p className="mb-3"><strong style={{ color: '#1D2129' }}>为什么自律比智商更重要？</strong></p>
          <p className="mb-2">斯坦福大学 Marshmallow 实验表明，能够延迟满足的儿童在成年后普遍拥有更高的生活满意度和职业成就。</p>
          <p>《原子习惯》作者 James Clear 提出，习惯的改变不在于目标，而在于身份认同的转变...</p>
        </div>
      </PreviewModule>

      <PreviewModule module="layout" selected={selectedModule === 'layout'} onSelect={setSelectedModule}>
        <div className="px-5 py-4 transition-colors" style={{ background: '#FFFFFF' }}>
          <p className="mb-2 text-xs font-semibold" style={{ color: '#1D2129' }}>排版效果预览</p>
          <div className="space-y-3">
            <div className="h-px" style={{ background: '#E5E6EB' }} />
            <div className="rounded-lg px-3 py-2" style={{ background: '#F7F8FA', borderLeft: '3px solid #165DFF' }}>
              <p className="mb-1 text-xs font-semibold" style={{ color: '#1D2129' }}>习惯一：每日5分钟冥想</p>
              <p className="text-[11px] leading-relaxed" style={{ color: '#86909C' }}>操作步骤 + 新手常见误区</p>
            </div>
            <div className="rounded-lg px-3 py-2" style={{ background: '#F7F8FA', borderLeft: '3px solid #FF2D5E' }}>
              <p className="mb-1 text-xs font-semibold" style={{ color: '#1D2129' }}>习惯二：番茄工作法</p>
              <p className="text-[11px] leading-relaxed" style={{ color: '#86909C' }}>25分钟专注 + 5分钟休息的节奏</p>
            </div>
            <div className="h-px" style={{ background: '#E5E6EB' }} />
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
  return (
    <Layout
      activeNav="dashboard"
      breadcrumbs={[{ label: '工作流仪表盘', href: '/' }, { label: '个人成长选题' }]}
      headerRight={
        <div className="flex items-center gap-1.5 text-xs" style={{ color: '#86909C' }}>
          <span
            className="h-1.5 w-1.5 rounded-full animate-pulse-dot"
            style={{ background: '#165DFF' }}
          />
          <span>实时同步</span>
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
            {PIPELINE_STAGES.map((stage, i) => {
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
                        {stage.port}
                      </div>
                    </div>
                  </button>

                  {i < PIPELINE_STAGES.length - 1 && (
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
                className="flex flex-shrink-0 items-center gap-2 border-t px-4 py-3"
                style={{ borderColor: '#E5E6EB', background: '#FFFFFF' }}
              >
                <button
                  onClick={() =>
                    showToast(`正在重新调用 Content Agent 重新编排 ${MODULE_LABELS[selectedModule]}...`, '#165DFF')
                  }
                  className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors"
                  style={{ background: '#F2F3F5', color: '#4E5969' }}
                >
                  <IconRefresh className="h-3.5 w-3.5" />
                  重新生成
                </button>
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
                  编辑内容
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
            )}
          </div>
        </div>

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
                      选题策划 - Topic Agent
                    </h3>
                    <span
                      className="inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-medium"
                      style={{ background: '#FFF7E8', color: '#FF7D00', border: '1px solid #FFCF8B' }}
                    >
                      <WarnFilled className="h-3 w-3" />
                      需要你的确认
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

            {/* Two columns: Agent Output Preview + Review Checklist */}
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
                  <div className="space-y-3">
                    <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
                      选题建议 (3个)
                    </div>
                    <div className="space-y-2">
                      {REVIEW_TOPICS.map((t, i) => (
                        <div
                          key={i}
                          className="flex items-start gap-3 rounded-lg border p-3"
                          style={{ borderColor: '#E5E6EB' }}
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
                    </div>
                    <div className="text-xs" style={{ color: '#86909C' }}>
                      竞品分析: 同类内容在小红书平均 1.2K 赞，微信公众号打开率 8.5%
                    </div>
                  </div>
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
                    {REVIEW_CHECKLIST.map((item, i) => (
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
                      onClick={handleApprove}
                      className="flex w-full cursor-pointer items-center justify-center gap-2 rounded-lg border-none text-sm font-medium text-white transition-all"
                      style={{ background: '#00B42A', padding: '8px 24px' }}
                    >
                      <IconCheck className="h-4 w-4" />
                      通过
                    </button>
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
    </Layout>
  )
}
