import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'

/* ------------------------------------------------------------------ */
/*  Types & Mock Data (mirrors canvas-designs/work-detail.html)        */
/* ------------------------------------------------------------------ */

interface ChatMessage {
  role: 'agent' | 'user'
  text: string
  time: string
}

interface PipelineStep {
  id: string
  name: string
  status: 'completed'
  summary: string
  detail: string
  tags: string[]
  wordCount?: string
  chatHistory: ChatMessage[]
}

interface PlatformMetric {
  name: string
  color: string
  views: number
  likes: number
  comments: number
  shares: number
}

interface StatusStyle {
  bg: string
  color: string
  border: string
  label: string
}

const MOCK_WORK = {
  title: '如何克服拖延症',
  status: '已发布',
  date: '2026-07-23',
  platforms: [
    { name: '小红书', bg: '#FFF0F5', color: '#FF2D5E' },
    { name: '公众号', bg: '#E8F3FF', color: '#165DFF' },
  ],
  totalViews: 12580,
  totalLikes: 892,
  totalComments: 156,
  totalShares: 67,
  platformMetrics: [
    { name: '小红书', color: '#FF2D5E', views: 8230, likes: 623, comments: 112, shares: 45 },
    { name: '公众号', color: '#165DFF', views: 4350, likes: 269, comments: 44, shares: 22 },
  ] as PlatformMetric[],
  weeklyViews: [1200, 1800, 950, 2100, 1650, 2300, 2580],
}

const PIPELINE_STEPS: PipelineStep[] = [
  {
    id: 'step-topic',
    name: '选题策划',
    status: 'completed',
    summary: '拖延症的自我救赎',
    detail: '围绕年轻职场人常见的拖延问题，从心理学角度分析原因并提供实操方法论。',
    tags: ['拖延症', '时间管理', '自律'],
    chatHistory: [
      { role: 'agent', text: '你好！我是选题策划助手。请告诉我你想创作的主题方向，我会帮你分析选题潜力和受众匹配度。', time: '14:02' },
      { role: 'user', text: '我想写关于拖延症的内容，目标读者是年轻职场人。', time: '14:03' },
      { role: 'agent', text: '好的选题方向！"拖延症"在小红书和公众号上都有很高的搜索热度。我为你规划了以下选题角度：\n\n1. 拖延症的心理学解读（科普类）\n2. 5个方法让我告别拖延（实操类）\n3. 从拖延到自律的30天实验（经验类）\n\n建议选择角度2，实操类内容在小红书传播效果最好，收藏率通常较高。你觉得怎么样？', time: '14:04' },
      { role: 'user', text: '选角度2吧，帮我生成选题大纲。', time: '14:05' },
      { role: 'agent', text: '已为你生成选题策划方案：\n\n**标题**: 5个方法让你告别拖延\n**目标受众**: 22-35岁职场人\n**关键词**: 拖延症、时间管理、自律、职场效率\n**预计字数**: 2000-2500字\n\n大纲已就绪，你可以进入下一步"内容创作"了。', time: '14:06' },
    ],
  },
  {
    id: 'step-content',
    name: '内容创作',
    status: 'completed',
    summary: '2,450字正文',
    detail: '围绕"两分钟法则"、"时间块工作法"和"环境设计"三大核心方法论展开。',
    tags: [],
    wordCount: '2,450',
    chatHistory: [
      { role: 'agent', text: '内容创作助手已就绪。基于选题策划方案，我将为你生成初稿。预计2-3分钟完成。', time: '14:10' },
      { role: 'agent', text: '初稿已生成（2,450字），主要内容包括：\n\n1. 开篇引入：拖延症的普遍现象\n2. 心理学分析：为什么我们会拖延\n3. 方法一：两分钟法则\n4. 方法二：时间块工作法\n5. 方法三：环境设计\n6. 总结与行动清单\n\n你可以提出修改意见，或直接进入下一步。', time: '14:13' },
      { role: 'user', text: '第三部分"时间块工作法"写得太简略了，能补充一些具体的时间分配案例吗？', time: '14:15' },
      { role: 'agent', text: '已补充！新增了"时间块工作法"的实操案例：\n\n- 上午9-11点：深度工作块（处理最重要的任务）\n- 11-11:30：短休息块（散步/拉伸）\n- 下午1-3点：协作沟通块（会议/回复消息）\n- 3-4点：学习输入块（阅读/笔记）\n\n字数增加至2,680字，内容更加充实。', time: '14:16' },
    ],
  },
  {
    id: 'step-images',
    name: '配图设计',
    status: 'completed',
    summary: '3张配图已生成',
    detail: '封面配图 + 2张内页配图，风格统一。',
    tags: [],
    chatHistory: [
      { role: 'agent', text: '配图设计助手已就绪。我将根据文章内容自动生成配套插图。', time: '14:20' },
      { role: 'agent', text: '已生成3张配图：\n\n1. **封面配图** - 渐变背景 + 大标题"5个方法告别拖延"\n2. **内页配图1** - 时间块工作法示意图\n3. **内页配图2** - 两分钟法则流程图\n\n风格统一，配色与文章调性匹配。如需调整请告知。', time: '14:23' },
      { role: 'user', text: '封面配图能否换成更温暖的配色？', time: '14:25' },
      { role: 'agent', text: '已调整封面配色方案：从冷色调改为暖橙色系渐变，更符合"积极行动"的主题氛围。预览已更新，请查看。', time: '14:26' },
    ],
  },
  {
    id: 'step-cover',
    name: '封面设计',
    status: 'completed',
    summary: '1080x1440 小红书标准封面',
    detail: '符合小红书竖版封面规范，包含主标题和副标题。',
    tags: [],
    chatHistory: [
      { role: 'agent', text: '封面设计助手就绪。基于文章标题和配图素材，我将为你生成多个封面方案。', time: '14:30' },
      { role: 'agent', text: '已生成3套封面方案：\n\n- 方案A：大字报风格，醒目标题居中\n- 方案B：图文混排，左侧标题右侧配图\n- 方案C：渐变背景+极简文字\n\n当前默认方案A（1080x1440）。如需切换请告知。', time: '14:32' },
      { role: 'user', text: '用方案A吧，副标题加上"科学方法告别拖延"。', time: '14:33' },
      { role: 'agent', text: '已更新副标题为"科学方法告别拖延"，封面设计完成！', time: '14:34' },
    ],
  },
  {
    id: 'step-publish',
    name: '排版发布',
    status: 'completed',
    summary: '小红书 + 公众号 已发布',
    detail: '已同步发布到小红书和微信公众号。',
    tags: [],
    chatHistory: [
      { role: 'agent', text: '排版发布助手就绪。检测到已绑定以下平台：小红书、公众号。', time: '14:40' },
      { role: 'agent', text: '排版预览已生成：\n\n- 小红书版：适配竖版图文格式，已添加话题标签\n- 公众号版：适配文章排版，已设置封面图\n\n确认无误后可以一键发布。', time: '14:42' },
      { role: 'user', text: '确认发布到两个平台。', time: '14:43' },
      { role: 'agent', text: '发布成功！\n\n- 小红书：已发布，笔记链接已生成\n- 公众号：已发布，文章推送完成\n\n作品状态已更新为"已发布"。', time: '14:44' },
    ],
  },
]

const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/* ------------------------------------------------------------------ */
/*  Inline SVG Icons                                                   */
/* ------------------------------------------------------------------ */

function CheckIcon({ color }: { color: string }) {
  return (
    <svg className="w-4 h-4" style={{ color }} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 12.75 6 6 9-13.5" />
    </svg>
  )
}

function ChevronIcon({ expanded }: { expanded: boolean }) {
  return (
    <svg
      className="w-4 h-4 transition-transform duration-300"
      style={{ color: '#86909C', transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)' }}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
      strokeWidth={2.5}
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="m19.5 8.25-7.5 7.5-7.5-7.5" />
    </svg>
  )
}

function EditIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10" />
    </svg>
  )
}

function ChartIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 0 1 3 19.875v-6.75ZM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V8.625ZM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V4.125Z" />
    </svg>
  )
}

function CloseIcon() {
  return (
    <svg className="w-5 h-5" style={{ color: '#86909C' }} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
    </svg>
  )
}

function SendIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" />
    </svg>
  )
}

function ChatBubbleIcon({ className = 'w-4 h-4 text-white', color }: { className?: string; color?: string }) {
  return (
    <svg className={className} style={color ? { color } : undefined} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M8.625 12a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H8.25m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H12m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 0 1-2.555-.337A5.972 5.972 0 0 1 5.41 20.97a5.969 5.969 0 0 1-.474-.065 4.48 4.48 0 0 0 .978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25Z" />
    </svg>
  )
}

function SparklesIcon() {
  return (
    <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09Z" />
    </svg>
  )
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

const fmt = (n: number) => n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')

function getStatusStyle(status: string): StatusStyle {
  if (status === 'completed') return { bg: '#E8F8F0', color: '#00B42A', border: '#00B42A', label: '已完成' }
  if (status === 'running') return { bg: '#E8F3FF', color: '#165DFF', border: '#165DFF', label: '进行中' }
  if (status === 'pending') return { bg: '#F2F3F5', color: '#86909C', border: '#C9CDD4', label: '等待中' }
  if (status === 'error') return { bg: '#FFECE8', color: '#F53F3F', border: '#F53F3F', label: '失败' }
  return { bg: '#F2F3F5', color: '#86909C', border: '#C9CDD4', label: status }
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export function WorkDetailPage() {
  const navigate = useNavigate()
  const [pipelineExpanded, setPipelineExpanded] = useState(false)
  const [activeStepId, setActiveStepId] = useState<string | null>(null)
  const [chatVisible, setChatVisible] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [chatInput, setChatInput] = useState('')
  const [barOpacities, setBarOpacities] = useState<number[]>(MOCK_WORK.weeklyViews.map(() => 0))

  const completedCount = PIPELINE_STEPS.filter((s) => s.status === 'completed').length
  const totalCount = PIPELINE_STEPS.length
  const activeStep = PIPELINE_STEPS.find((s) => s.id === activeStepId) ?? PIPELINE_STEPS[0]
  const maxWeekly = Math.max(...MOCK_WORK.weeklyViews)

  /* Staggered bar entrance animation (mirrors design renderWeeklyChart) */
  useEffect(() => {
    const timers: number[] = []
    MOCK_WORK.weeklyViews.forEach((_, i) => {
      timers.push(
        window.setTimeout(() => {
          setBarOpacities((prev) => {
            const next = [...prev]
            next[i] = 1
            return next
          })
        }, 100 + i * 80),
      )
    })
    return () => timers.forEach((t) => window.clearTimeout(t))
  }, [])

  const openChat = (stepId: string) => {
    const step = PIPELINE_STEPS.find((s) => s.id === stepId)
    if (!step) return
    setActiveStepId(stepId)
    setMessages(step.chatHistory)
    setChatVisible(true)
    if (!pipelineExpanded) setPipelineExpanded(true)
  }

  const closeChat = () => {
    setChatVisible(false)
    setActiveStepId(null)
  }

  const sendMessage = () => {
    const text = chatInput.trim()
    if (!text) return
    setMessages((prev) => [...prev, { role: 'user', text, time: '刚刚' }])
    setChatInput('')
    window.setTimeout(() => {
      setMessages((prev) => [
        ...prev,
        {
          role: 'agent',
          text: '收到你的修改意见，正在处理中...我已记录你的反馈，将据此调整内容。处理完成后会通知你。',
          time: '刚刚',
        },
      ])
    }, 1200)
  }

  const togglePipeline = (e: React.MouseEvent) => {
    // Only toggle when clicking outside a step node (mirrors design bindEvents)
    if (!(e.target as HTMLElement).closest('.step-node')) {
      setPipelineExpanded((v) => !v)
    }
  }

  return (
    <Layout
      activeNav="works"
      breadcrumbs={[
        { label: '作品中心', href: '/work-center' },
        { label: '作品详情' },
      ]}
    >
      <div className="flex gap-6">
        {/* ============================================================ */}
        {/*  LEFT COLUMN (2/3)                                            */}
        {/* ============================================================ */}
        <div className="flex-1 space-y-6" style={{ minWidth: 0, flexBasis: 'calc(66.667% - 12px)' }}>

          {/* ===== 1. WORK HEADER CARD ===== */}
          <section
            className="card overflow-hidden animate-fadeInUp"
            style={{ animation: 'fadeInUp 400ms ease both' }}
            aria-label="作品信息"
          >
            {/* Cover Image */}
            <div className="relative" style={{ height: 200, background: 'linear-gradient(135deg, #FF2D5E, #FF5C8A)' }}>
              <div
                className="absolute inset-0 flex items-end p-5"
                style={{ background: 'linear-gradient(to top, rgba(0,0,0,0.35), transparent)' }}
              >
                <div>
                  <h1 className="font-bold text-white mb-2" style={{ fontSize: 24 }}>
                    {MOCK_WORK.title}
                  </h1>
                  <div className="flex items-center gap-3">
                    <span
                      className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium"
                      style={{ background: '#00B42A', color: '#fff' }}
                    >
                      {MOCK_WORK.status}
                    </span>
                    <span className="text-sm text-white" style={{ opacity: 0.9 }}>
                      {MOCK_WORK.date}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            {/* Info Body */}
            <div className="p-5">
              {/* Platform Tags */}
              <div className="flex items-center gap-2 mb-4">
                <span className="text-sm font-medium" style={{ color: '#4E5969' }}>
                  发布平台:
                </span>
                <div className="flex items-center gap-2">
                  {MOCK_WORK.platforms.map((p) => (
                    <span
                      key={p.name}
                      className="inline-flex items-center px-2.5 py-1 rounded-lg text-xs font-medium"
                      style={{ background: p.bg, color: p.color }}
                    >
                      {p.name}
                    </span>
                  ))}
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center gap-3">
                <button className="btn-outline">
                  <EditIcon />
                  编辑作品
                </button>
                <button className="btn-primary" onClick={() => navigate('/data-center')}>
                  <ChartIcon />
                  查看数据
                </button>
              </div>
            </div>
          </section>

          {/* ===== 2. AGENT PIPELINE (Collapsible) ===== */}
          <section
            className="card overflow-hidden"
            style={{ animation: 'fadeInUp 500ms ease both' }}
            aria-label="Agent创作管线"
          >
            {/* Pipeline Header: Compact Progress Bar (always visible) */}
            <div className="p-5 cursor-pointer select-none" onClick={togglePipeline}>
              <div className="flex items-center justify-between mb-4">
                <h2 className="font-semibold" style={{ color: '#1D2129', fontSize: 16 }}>
                  Agent 创作管线
                </h2>
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium" style={{ color: '#86909C' }}>
                    当前进度
                  </span>
                  <span
                    className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold"
                    style={{ background: '#E8F8F0', color: '#00B42A' }}
                  >
                    {completedCount}/{totalCount} 已完成
                  </span>
                  <ChevronIcon expanded={pipelineExpanded} />
                </div>
              </div>

              {/* Compact Progress Bar */}
              <div className="flex items-start gap-0">
                {PIPELINE_STEPS.map((step, index) => {
                  const st = getStatusStyle(step.status)
                  const isActive = activeStepId === step.id
                  const isLast = index === PIPELINE_STEPS.length - 1
                  return (
                    <div
                      key={step.id}
                      className="step-node flex flex-col items-center cursor-pointer transition-all duration-200"
                      style={{ flex: 1, minWidth: 0 }}
                      onClick={(e) => {
                        e.stopPropagation()
                        openChat(step.id)
                      }}
                    >
                      {/* Icon + connector row */}
                      <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
                        <div
                          className="step-circle rounded-full flex items-center justify-center flex-shrink-0 transition-all duration-200"
                          style={{
                            width: 32,
                            height: 32,
                            background: st.bg,
                            border: `2px solid ${st.color}`,
                            ...(isActive ? { boxShadow: `0 0 0 4px ${st.bg}` } : {}),
                          }}
                        >
                          <CheckIcon color={st.color} />
                        </div>
                        {!isLast && (
                          <div
                            className="step-connector"
                            style={{ flex: 1, height: 3, borderRadius: 2, margin: '0 2px', marginTop: 14, background: st.color }}
                          />
                        )}
                      </div>
                      {/* Step name */}
                      <span
                        className="text-xs font-medium mt-2 text-center transition-colors duration-200"
                        style={{
                          color: isActive ? st.color : '#4E5969',
                          maxWidth: 64,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {step.name}
                      </span>
                    </div>
                  )
                })}
              </div>

              {/* Hint text */}
              <p className="text-xs mt-3" style={{ color: '#C9CDD4' }}>
                点击展开查看详情，或点击步骤进入对话修改
              </p>
            </div>

            {/* Expandable Detail Panel */}
            <div
              style={{
                maxHeight: pipelineExpanded ? '3000px' : '0',
                opacity: pipelineExpanded ? 1 : 0,
                overflow: 'hidden',
                transition: 'max-height 400ms ease, opacity 400ms ease',
              }}
            >
              <div className="border-t p-5" style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}>
                {/* Timeline Connector */}
                <div className="relative">
                  <div
                    className="absolute w-px"
                    style={{ left: 15, top: 12, bottom: 12, background: '#E5E6EB' }}
                  />
                  <div className="space-y-5">
                    {PIPELINE_STEPS.map((step) => {
                      const st = getStatusStyle(step.status)
                      const isActive = activeStepId === step.id
                      return (
                        <div
                          key={step.id}
                          className="relative flex items-start gap-4 cursor-pointer"
                          onClick={() => openChat(step.id)}
                        >
                          {/* Timeline dot */}
                          <div
                            className="relative z-10 flex-shrink-0 rounded-full flex items-center justify-center"
                            style={{ width: 32, height: 32, background: st.bg, border: `2px solid ${st.color}` }}
                          >
                            <CheckIcon color={st.color} />
                          </div>
                          {/* Content card */}
                          <div
                            className="flex-1 rounded-xl border p-4 transition-all duration-200"
                            style={{ borderColor: isActive ? st.color : '#E5E6EB', background: isActive ? st.bg : '#FFFFFF' }}
                          >
                            <div className="flex items-center justify-between mb-2">
                              <div className="flex items-center gap-2">
                                <span className="text-sm font-semibold" style={{ color: '#1D2129' }}>
                                  {step.name}
                                </span>
                                <span
                                  className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                                  style={{ background: st.bg, color: st.color }}
                                >
                                  {st.label}
                                </span>
                              </div>
                              {step.wordCount && (
                                <span className="text-xs tabular-nums" style={{ color: '#86909C' }}>
                                  {step.wordCount}
                                </span>
                              )}
                            </div>
                            <p className="text-sm" style={{ color: '#4E5969' }}>
                              {step.summary}
                            </p>
                            {step.detail && (
                              <p className="text-xs mt-1" style={{ color: '#86909C' }}>
                                {step.detail}
                              </p>
                            )}
                            {step.tags.length > 0 && (
                              <div className="flex items-center gap-1.5 mt-2">
                                {step.tags.map((tag) => (
                                  <span
                                    key={tag}
                                    className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
                                    style={{ background: '#F2F3F5', color: '#4E5969' }}
                                  >
                                    {tag}
                                  </span>
                                ))}
                              </div>
                            )}
                            {/* Action hint */}
                            <div className="flex items-center gap-1 mt-3">
                              <ChatBubbleIcon className="w-3.5 h-3.5" color={st.color} />
                              <span className="text-xs font-medium" style={{ color: st.color }}>
                                点击查看对话记录
                              </span>
                            </div>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </div>
              </div>
            </div>
          </section>

          {/* ===== 3. CHAT PANEL (shown when a step is clicked) ===== */}
          {chatVisible && (
            <section
              className="card overflow-hidden animate-fadeInUp"
              style={{ animation: 'fadeInUp 400ms ease both' }}
              aria-label="对话记录"
            >
              {/* Chat Header */}
              <div className="flex items-center justify-between px-5 py-4 border-b" style={{ borderColor: '#E5E6EB' }}>
                <div className="flex items-center gap-3">
                  <div
                    className="w-8 h-8 rounded-full flex items-center justify-center"
                    style={{ background: 'linear-gradient(135deg, #165DFF, #4080FF)' }}
                  >
                    <ChatBubbleIcon className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h3 className="text-sm font-semibold" style={{ color: '#1D2129' }}>
                      {activeStep.name} - 对话记录
                    </h3>
                    <p className="text-xs" style={{ color: '#86909C' }}>
                      点击步骤查看对应的 Agent 对话
                    </p>
                  </div>
                </div>
                <button
                  className="p-1.5 rounded-lg transition-colors hover:bg-[#F2F3F5]"
                  onClick={closeChat}
                  aria-label="关闭对话"
                >
                  <CloseIcon />
                </button>
              </div>

              {/* Chat Messages Area */}
              <div
                className="custom-scrollbar p-5 space-y-4"
                style={{ height: 360, overflowY: 'auto' }}
              >
                {messages.map((msg, i) =>
                  msg.role === 'agent' ? (
                    <div key={i} className="flex items-start gap-3" style={{ animation: 'fadeInUp 200ms ease both' }}>
                      <div
                        className="flex-shrink-0 w-7 h-7 rounded-full flex items-center justify-center"
                        style={{ background: 'linear-gradient(135deg, #165DFF, #4080FF)' }}
                      >
                        <SparklesIcon />
                      </div>
                      <div className="flex-1">
                        <div
                          className="rounded-xl p-3.5"
                          style={{ background: '#F2F3F5', maxWidth: '85%', borderTopLeftRadius: 4 }}
                        >
                          <p className="text-sm leading-relaxed" style={{ color: '#1D2129', whiteSpace: 'pre-line' }}>
                            {msg.text}
                          </p>
                        </div>
                        <span className="text-xs mt-1 inline-block" style={{ color: '#C9CDD4' }}>
                          {msg.time}
                        </span>
                      </div>
                    </div>
                  ) : (
                    <div
                      key={i}
                      className="flex items-start gap-3 justify-end"
                      style={{ animation: 'fadeInUp 200ms ease both' }}
                    >
                      <div className="flex-1 flex flex-col items-end">
                        <div
                          className="rounded-xl p-3.5"
                          style={{ background: '#165DFF', maxWidth: '85%', borderTopRightRadius: 4 }}
                        >
                          <p className="text-sm leading-relaxed text-white" style={{ whiteSpace: 'pre-line' }}>
                            {msg.text}
                          </p>
                        </div>
                        <span className="text-xs mt-1 inline-block" style={{ color: '#C9CDD4' }}>
                          {msg.time}
                        </span>
                      </div>
                      <div
                        className="flex-shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-white text-xs font-bold"
                        style={{ background: 'linear-gradient(135deg, #FF2D5E, #FF5C8A)' }}
                      >
                        U
                      </div>
                    </div>
                  ),
                )}
              </div>

              {/* Chat Input Area */}
              <div className="border-t p-4" style={{ borderColor: '#E5E6EB' }}>
                <div className="flex items-center gap-3">
                  <input
                    type="text"
                    value={chatInput}
                    onChange={(e) => setChatInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') sendMessage()
                    }}
                    placeholder="输入修改意见或追问..."
                    className="input-field"
                    style={{ flex: 1, padding: '10px 16px' }}
                  />
                  <button className="btn-primary" onClick={sendMessage} disabled={!chatInput.trim()}>
                    <SendIcon />
                    发送
                  </button>
                </div>
              </div>
            </section>
          )}
        </div>

        {/* ============================================================ */}
        {/*  RIGHT COLUMN (1/3)                                           */}
        {/* ============================================================ */}
        <div className="flex-shrink-0 space-y-6" style={{ width: 'calc(33.333% - 12px)' }}>

          {/* ===== 1. DATA OVERVIEW CARD ===== */}
          <section
            className="card p-5 animate-fadeInUp"
            style={{ animation: 'fadeInUp 400ms ease both' }}
            aria-label="数据概览"
          >
            <h2 className="font-semibold mb-5" style={{ color: '#1D2129', fontSize: 16 }}>
              数据概览
            </h2>

            <div className="text-center mb-5">
              <p className="text-xs font-medium mb-1" style={{ color: '#86909C' }}>
                总浏览量
              </p>
              <p className="font-bold tabular-nums leading-none" style={{ color: '#1D2129', fontSize: 36 }}>
                {fmt(MOCK_WORK.totalViews)}
              </p>
            </div>

            <div className="grid grid-cols-3 gap-3">
              <div className="text-center p-3 rounded-lg" style={{ background: '#F7F8FA' }}>
                <p className="text-xs mb-1" style={{ color: '#86909C' }}>
                  点赞
                </p>
                <p className="text-lg font-bold tabular-nums" style={{ color: '#1D2129' }}>
                  {fmt(MOCK_WORK.totalLikes)}
                </p>
              </div>
              <div className="text-center p-3 rounded-lg" style={{ background: '#F7F8FA' }}>
                <p className="text-xs mb-1" style={{ color: '#86909C' }}>
                  评论
                </p>
                <p className="text-lg font-bold tabular-nums" style={{ color: '#1D2129' }}>
                  {fmt(MOCK_WORK.totalComments)}
                </p>
              </div>
              <div className="text-center p-3 rounded-lg" style={{ background: '#F7F8FA' }}>
                <p className="text-xs mb-1" style={{ color: '#86909C' }}>
                  转发
                </p>
                <p className="text-lg font-bold tabular-nums" style={{ color: '#1D2129' }}>
                  {fmt(MOCK_WORK.totalShares)}
                </p>
              </div>
            </div>
          </section>

          {/* ===== 2. PLATFORM METRICS CARD ===== */}
          <section
            className="card p-5 animate-fadeInUp"
            style={{ animation: 'fadeInUp 500ms ease both' }}
            aria-label="各平台数据"
          >
            <h2 className="font-semibold mb-4" style={{ color: '#1D2129', fontSize: 16 }}>
              各平台数据
            </h2>

            <table className="w-full">
              <thead>
                <tr style={{ borderBottom: '1px solid #E5E6EB' }}>
                  <th className="text-left text-xs font-medium pb-3" style={{ color: '#86909C' }}>
                    平台
                  </th>
                  <th className="text-right text-xs font-medium pb-3" style={{ color: '#86909C' }}>
                    浏览量
                  </th>
                  <th className="text-right text-xs font-medium pb-3" style={{ color: '#86909C' }}>
                    点赞
                  </th>
                  <th className="text-right text-xs font-medium pb-3" style={{ color: '#86909C' }}>
                    评论
                  </th>
                  <th className="text-right text-xs font-medium pb-3" style={{ color: '#86909C' }}>
                    转发
                  </th>
                </tr>
              </thead>
              <tbody>
                {MOCK_WORK.platformMetrics.map((p) => (
                  <tr key={p.name} style={{ borderBottom: '1px solid #F2F3F5' }}>
                    <td className="py-3">
                      <div className="flex items-center gap-2">
                        <div className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ background: p.color }} />
                        <span className="text-sm font-medium" style={{ color: '#1D2129' }}>
                          {p.name}
                        </span>
                      </div>
                    </td>
                    <td className="text-right py-3 text-sm tabular-nums" style={{ color: '#1D2129' }}>
                      {fmt(p.views)}
                    </td>
                    <td className="text-right py-3 text-sm tabular-nums" style={{ color: '#1D2129' }}>
                      {fmt(p.likes)}
                    </td>
                    <td className="text-right py-3 text-sm tabular-nums" style={{ color: '#1D2129' }}>
                      {fmt(p.comments)}
                    </td>
                    <td className="text-right py-3 text-sm tabular-nums" style={{ color: '#1D2129' }}>
                      {fmt(p.shares)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {/* ===== 3. DATA TREND CARD (CSS Bar Chart) ===== */}
          <section
            className="card p-5 animate-fadeInUp"
            style={{ animation: 'fadeInUp 600ms ease both' }}
            aria-label="数据趋势"
          >
            <h2 className="font-semibold mb-4" style={{ color: '#1D2129', fontSize: 16 }}>
              近7日浏览趋势
            </h2>

            {/* CSS Bar Chart */}
            <div className="flex items-end justify-between gap-2" style={{ height: 120 }}>
              {MOCK_WORK.weeklyViews.map((val, i) => {
                const heightPercent = Math.max(5, (val / maxWeekly) * 100)
                const barColor = i === MOCK_WORK.weeklyViews.length - 1 ? '#FF2D5E' : '#C9CDD4'
                return (
                  <div
                    key={i}
                    className="chart-bar"
                    data-value={fmt(val)}
                    style={{
                      height: `${heightPercent}%`,
                      background: barColor,
                      opacity: barOpacities[i],
                      flex: 1,
                      minWidth: 0,
                      borderRadius: '4px 4px 0 0',
                      transition: 'height 600ms ease, opacity 400ms ease',
                    }}
                  />
                )
              })}
            </div>
            {/* Day Labels */}
            <div className="flex items-center justify-between gap-2 mt-2">
              {DAY_LABELS.map((label, i) => (
                <span key={i} className="text-xs text-center" style={{ color: '#86909C', flex: 1 }}>
                  {label}
                </span>
              ))}
            </div>
          </section>
        </div>
      </div>
    </Layout>
  )
}
