import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import { startWorkflow } from '@/api/workflow'
import { listCollections } from '@/api/collections'
import { listTrends } from '@/api/trends'
import { trackWorkflow } from '@/utils/workflowTracker'
import type {
  StartWorkflowRequest,
  AccountProfile,
  PublishMode,
  WorkCollection,
  TrendHotspot,
} from '@/types'

/* 热点监控支持的热榜平台 */
const TREND_PLATFORMS: { code: string; name: string }[] = [
  { code: 'xiaohongshu', name: '小红书' },
  { code: 'weibo', name: '微博' },
  { code: 'douyin', name: '抖音' },
  { code: 'bilibili', name: '哔哩哔哩' },
  { code: 'zhihu', name: '知乎' },
]

/* ================================================================
   Custom CSS — mirrors the design file's <style> block exactly
   ================================================================ */
const CUSTOM_CSS = `
  /* Account Row */
  .account-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border: 1px solid #E5E6EB; border-radius: 8px; background: #fff; transition: border-color 150ms ease; }
  .account-row:hover { border-color: #C9CDD4; }
  .account-row-left { display: flex; align-items: center; gap: 10px; }
  .account-row-right { display: flex; align-items: center; gap: 12px; }
  .account-platform-icon { width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
  .account-platform-name { font-size: 14px; font-weight: 500; color: #1D2129; }
  .account-select { border: 1px solid #E5E6EB; border-radius: 8px; padding: 6px 32px 6px 12px; font-size: 13px; color: #1D2129; background: #fff; cursor: pointer; transition: border-color 150ms ease; appearance: none; -webkit-appearance: none; background-image: url("data:image/svg+xml,%3Csvg width='12' height='12' fill='none' stroke='%2386909C' viewBox='0 0 24 24' stroke-width='2' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath stroke-linecap='round' stroke-linejoin='round' d='m19.5 8.25-7.5 7.5-7.5-7.5'/%3E%3C/svg%3E"); background-repeat: no-repeat; background-position: right 10px center; min-width: 200px; }
  .account-select:focus { border-color: #165DFF; box-shadow: 0 0 0 2px rgba(22, 93, 255, 0.1); outline: none; }
  .account-settings-link { font-size: 12px; color: #165DFF; text-decoration: none; cursor: pointer; white-space: nowrap; background: none; border: none; padding: 0; font-family: inherit; }
  .account-settings-link:hover { text-decoration: underline; }

  /* Pipeline Stage */
  .pipeline-stage { position: relative; display: flex; align-items: center; gap: 12px; padding-bottom: 16px; }
  .pipeline-stage:not(:last-child)::after { content: ''; position: absolute; left: 11px; top: 24px; bottom: 0; width: 1px; background: #E5E6EB; }
  .pipeline-icon { width: 24px; height: 24px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #FF2D5E, #FF5C8A); color: #fff; z-index: 1; }
  .pipeline-info { display: flex; align-items: center; gap: 8px; flex: 1; }

  /* Error Text */
  .error-text { font-size: 12px; color: #F53F3F; margin-top: 4px; min-height: 16px; }
`

/* ================================================================
   Platform configuration
   ================================================================ */
interface AccountOption {
  value: string
  label: string
  name: string
}

interface PlatformConfig {
  name: string
  color: string
  icon: React.ReactNode
  accounts: AccountOption[]
}

const PLATFORMS: PlatformConfig[] = [
  {
    name: '小红书',
    color: '#FF2D5E',
    icon: (
      <svg width="14" height="14" fill="none" stroke="#fff" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 1 0-7.5 0v4.5m11.356-1.993 1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 0 1-1.12-1.243l1.264-12A1.125 1.125 0 0 1 5.513 7.5h12.974c.576 0 1.059.435 1.119 1.007ZM8.625 10.5a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm7.5 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Z" />
      </svg>
    ),
    accounts: [
      { value: 'xhs-001', label: '成长日记 (xhs-001)', name: '成长日记' },
      { value: 'xhs-002', label: '职场小白 (xhs-002)', name: '职场小白' },
    ],
  },
  {
    name: '公众号',
    color: '#165DFF',
    icon: (
      <svg width="14" height="14" fill="none" stroke="#fff" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z" />
      </svg>
    ),
    accounts: [
      { value: 'gh-001', label: '干货分享站 (gh-001)', name: '干货分享站' },
    ],
  },
  {
    name: '抖音',
    color: '#FE2C55',
    icon: (
      <svg width="14" height="14" fill="none" stroke="#fff" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 7.5h1.5m-1.5 3h1.5m-7.5 3h7.5m-7.5 3h7.5m3-9h3.375c.621 0 1.125.504 1.125 1.125V18a2.25 2.25 0 0 1-2.25 2.25M16.5 7.5V18a2.25 2.25 0 0 0 2.25 2.25M16.5 7.5V4.875c0-.621-.504-1.125-1.125-1.125H4.125C3.504 3.75 3 4.254 3 4.875V18a2.25 2.25 0 0 0 2.25 2.25h13.5M6 7.5h3v3H6v-3Z" />
      </svg>
    ),
    accounts: [
      { value: 'dy-001', label: '短视频日记 (dy-001)', name: '短视频日记' },
    ],
  },
  {
    name: '哔哩哔哩',
    color: '#00A1D6',
    icon: (
      <svg width="14" height="14" fill="none" stroke="#fff" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="M4.26 10.147a60.438 60.438 0 0 0-.491 6.347A48.627 48.627 0 0 1 12 20.904a48.627 48.627 0 0 1 8.232-4.41 60.46 60.46 0 0 0-.491-6.347m-15.482 0a50.636 50.636 0 0 0-2.658-.813A59.906 59.906 0 0 1 12 3.493a59.903 59.903 0 0 1 10.399 5.84c-.896.248-1.783.52-2.658.814m-15.482 0A50.717 50.717 0 0 1 12 13.489a50.702 50.702 0 0 1 7.74-3.342M6.75 15a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm0 0v-3.675A55.378 55.378 0 0 1 12 8.443m-7.007 11.55A5.981 5.981 0 0 0 6.75 15.75v-1.5" />
      </svg>
    ),
    accounts: [
      { value: 'bl-001', label: '二次元笔记 (bl-001)', name: '二次元笔记' },
    ],
  },
]

/* ================================================================
   Select options
   ================================================================ */
const NICHE_OPTIONS = [
  { value: 'personal-growth', label: '个人成长' },
  { value: 'career', label: '职场干货' },
  { value: 'finance', label: '理财投资' },
  { value: 'health', label: '健康养生' },
  { value: 'tech', label: '科技数码' },
  { value: 'education', label: '教育学习' },
  { value: 'lifestyle', label: '生活方式' },
  { value: 'emotion', label: '情感心理' },
  { value: 'travel', label: '旅行探索' },
  { value: 'food', label: '美食烹饪' },
]

const AUDIENCE_OPTIONS = [
  { value: 'young-adult', label: '20-30岁年轻人' },
  { value: 'student', label: '大学生' },
  { value: 'workplace', label: '职场新人' },
  { value: 'senior', label: '职场老手' },
  { value: 'parent', label: '新手父母' },
  { value: 'entrepreneur', label: '创业者' },
  { value: 'freelancer', label: '自由职业者' },
]

const TONE_OPTIONS = [
  { value: 'professional', label: '专业严谨' },
  { value: 'casual', label: '轻松活泼' },
  { value: 'inspiring', label: '励志正能量' },
  { value: 'humorous', label: '幽默风趣' },
  { value: 'warm', label: '温暖治愈' },
  { value: 'practical', label: '实用干货' },
  { value: 'storytelling', label: '故事叙述' },
]

/* ================================================================
   Pipeline stage preview
   ================================================================ */
interface PipelineStage {
  name: string
  port: string
  icon: React.ReactNode
  loop?: boolean
}

const PIPELINE_STAGES: PipelineStage[] = [
  {
    name: '选题策划',
    port: ':8081',
    icon: (
      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09Z" />
      </svg>
    ),
  },
  {
    name: '内容创作',
    port: ':8082',
    icon: (
      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125" />
      </svg>
    ),
  },
  {
    name: '配图设计',
    port: ':8083',
    icon: (
      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909m-18 3.75h16.5a1.5 1.5 0 0 0 1.5-1.5V6a1.5 1.5 0 0 0-1.5-1.5H3.75A1.5 1.5 0 0 0 2.25 6v12a1.5 1.5 0 0 0 1.5 1.5Zm10.5-11.25h.008v.008h-.008V8.25Zm.375 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Z" />
      </svg>
    ),
  },
  {
    name: '排版发布',
    port: ':8084',
    icon: (
      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2">
        <path strokeLinecap="round" strokeLinejoin="round" d="M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5" />
      </svg>
    ),
  },
]

/* ================================================================
   Helper: get label from value
   ================================================================ */
function getLabel(options: { value: string; label: string }[], value: string): string {
  return options.find((o) => o.value === value)?.label ?? ''
}

/* ================================================================
   Main component
   ================================================================ */
export function CreateWorkflowPage() {
  const navigate = useNavigate()

  /* ── State ── */
  const [selectedAccounts, setSelectedAccounts] = useState<Record<string, string>>({
    '小红书': '',
    '公众号': '',
    '抖音': '',
    '哔哩哔哩': '',
  })
  const [direction, setDirection] = useState('')
  const [humanReview, setHumanReview] = useState(false)
  const [publishMode, setPublishMode] = useState<PublishMode>('text-cover')
  const [niche, setNiche] = useState('')
  const [targetAudience, setTargetAudience] = useState('')
  const [tone, setTone] = useState('')
  const [collections, setCollections] = useState<WorkCollection[]>([])
  const [selectedCollectionIds, setSelectedCollectionIds] = useState<string[]>([])
  const [collectionsLoading, setCollectionsLoading] = useState(false)
  const [hotspots, setHotspots] = useState<TrendHotspot[]>([])
  const [trendPlatform, setTrendPlatform] = useState('xiaohongshu')
  const [trendLoading, setTrendLoading] = useState(false)
  const [trendError, setTrendError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [accountError, setAccountError] = useState('')
  const [submitError, setSubmitError] = useState('')

  /* ── 作品合集：创建时可指定归入哪些合集 ── */
  useEffect(() => {
    setCollectionsLoading(true)
    listCollections()
      .then((list) => setCollections(list))
      .catch(() => setCollections([]))
      .finally(() => setCollectionsLoading(false))
  }, [])

  /* ── 热点选题：从热点监控模块取热点，一键生成作品 ── */
  useEffect(() => {
    setTrendLoading(true)
    setTrendError('')
    listTrends(trendPlatform, 8)
      .then((list) => setHotspots(list))
      .catch((err: any) => {
        setTrendError(err?.message || '热点加载失败')
        setHotspots([])
      })
      .finally(() => setTrendLoading(false))
  }, [trendPlatform])

  /* ── Derived: list of selected accounts for preview ── */
  const selectedAccountsList = PLATFORMS
    .filter((p) => selectedAccounts[p.name])
    .map((p) => ({
      platform: p.name,
      color: p.color,
      accountName:
        p.accounts.find((a) => a.value === selectedAccounts[p.name])?.name ??
        selectedAccounts[p.name],
    }))

  /* ── Derived: option labels for preview ── */
  const nicheLabel = getLabel(NICHE_OPTIONS, niche)
  const audienceLabel = getLabel(AUDIENCE_OPTIONS, targetAudience)
  const toneLabel = getLabel(TONE_OPTIONS, tone)

  /* ── Handlers ── */
  const handleAccountChange = (platformName: string, value: string) => {
    setSelectedAccounts((prev) => ({ ...prev, [platformName]: value }))
    if (accountError) setAccountError('')
  }

  const handleToggleReview = () => setHumanReview((v) => !v)

  /* 组装创建请求；传入热点时用热点标题作为选题并记录来源 */
  const buildStartRequest = (hotspot?: TrendHotspot): StartWorkflowRequest => {
    const selectedPlatformNames = PLATFORMS
      .filter((p) => selectedAccounts[p.name])
      .map((p) => p.name)
    const firstAccount = PLATFORMS.find((p) => selectedAccounts[p.name])
    const accountValue = firstAccount ? selectedAccounts[firstAccount.name] : ''
    const inputs: Record<string, unknown> = {
      additionalContext: direction || undefined,
    }
    if (hotspot) {
      inputs.topic = hotspot.title
      inputs.hotspotSource = {
        id: hotspot.id,
        platform: hotspot.platform,
        title: hotspot.title,
        heat: hotspot.heat,
        category: hotspot.category,
      }
    }
    return {
      accountProfile: {
        accountId: accountValue || `acc-${Date.now()}`,
        accountName:
          firstAccount?.accounts.find((a) => a.value === accountValue)?.name ||
          firstAccount?.name ||
          '默认账号',
        niche: nicheLabel || niche,
        targetAudience: audienceLabel || targetAudience,
        tone: toneLabel || tone,
        platforms: selectedPlatformNames,
      },
      inputs,
      collectionIds: selectedCollectionIds,
      publishMode,
      platformAccounts: Object.fromEntries(
        PLATFORMS.filter((p) => selectedAccounts[p.name]).map((p) => {
          const acc = p.accounts.find((a) => a.value === selectedAccounts[p.name])
          return [
            p.name,
            { accountId: selectedAccounts[p.name], accountName: acc?.name ?? p.name },
          ]
        })
      ),
      requireHumanReview: humanReview,
    }
  }

  const handleSubmit = async () => {
    setAccountError('')
    setSubmitError('')
    setSubmitting(true)
    try {
      const result = await startWorkflow(buildStartRequest())
      trackWorkflow(result.workflowId, direction || nicheLabel || niche)
      navigate(`/workflow-detail?workflowId=${result.workflowId}`)
    } catch (err: any) {
      setSubmitError(err?.message || '启动工作流失败，请重试')
    } finally {
      setSubmitting(false)
    }
  }

  /* 热点选题：直接基于热点标题一键生成作品 */
  const handleHotspotCreate = async (hotspot: TrendHotspot) => {
    if (submitting) return
    setSubmitError('')
    setSubmitting(true)
    try {
      const result = await startWorkflow(buildStartRequest(hotspot))
      trackWorkflow(result.workflowId, hotspot.title)
      navigate(`/workflow-detail?workflowId=${result.workflowId}`)
    } catch (err: any) {
      setSubmitError(err?.message || '基于热点启动工作流失败，请重试')
    } finally {
      setSubmitting(false)
    }
  }

  const handleSaveDraft = () => {
    // Save draft — no critical action needed for now
  }

  const handleStartDiscussion = () => {
    const hasAccount = PLATFORMS.some((p) => selectedAccounts[p.name])
    if (!hasAccount) {
      setAccountError('请至少选择一个平台账号')
      return
    }
    if (!direction.trim()) return

    // 组装 AccountProfile（与 handleSubmit 一致）
    const selectedPlatformNames = PLATFORMS
      .filter((p) => selectedAccounts[p.name])
      .map((p) => p.name)
    const firstAccount = PLATFORMS.find((p) => selectedAccounts[p.name])
    const accountValue = firstAccount ? selectedAccounts[firstAccount.name] : ''

    const accountProfile: AccountProfile = {
      accountId: accountValue || `acc-${Date.now()}`,
      accountName:
        firstAccount?.accounts.find((a) => a.value === accountValue)?.name ||
        firstAccount?.name ||
        '默认账号',
      niche: nicheLabel || niche,
      targetAudience: audienceLabel || targetAudience,
      tone: toneLabel || tone,
      platforms: selectedPlatformNames,
    }

    // 跳转到讨论页面
    navigate('/discussion', {
      state: {
        accountProfile,
        fuzzyIdea: direction,
      },
    })
  }

  const charCount = direction.length
  const charColor = charCount > 500 ? '#F53F3F' : '#86909C'

  /* ── Render ── */
  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: CUSTOM_CSS }} />

      <Layout
        activeNav="create"
        breadcrumbs={[
          { label: '工作流仪表盘', href: '/' },
          { label: '创建工作流' },
        ]}
      >
        {/* ════════ Page Header ════════ */}
        <div className="mb-6" style={{ animation: 'fadeInUp 300ms ease both' }}>
          <h1 className="font-semibold" style={{ color: '#1D2129', fontSize: 16 }}>
            创建内容运营工作流
          </h1>
          <p className="mt-1" style={{ fontSize: 14, color: '#86909C' }}>
            配置账号信息，AI 将自动完成从选题到优化的全流程
          </p>
        </div>

        {/* ════════ Two-Column Layout ════════ */}
        <div
          className="mx-auto"
          style={{
            maxWidth: 1200,
            display: 'grid',
            gridTemplateColumns: '2fr 1fr',
            gap: 32,
            animation: 'fadeInUp 400ms ease both',
          }}
        >
          {/* ─────────── LEFT COLUMN (Form) ─────────── */}
          <div>
            {/* ===== Form Card ===== */}
            <div className="card" style={{ padding: 32, marginBottom: 24 }}>

              {/* a. 关联账号 Section */}
              <div style={{ marginBottom: 32 }}>
                <div style={{ marginBottom: 4 }}>
                  <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129' }}>关联账号</h2>
                  <p style={{ fontSize: 12, color: '#86909C', marginTop: 2 }}>
                    选择已配置的平台账号，AI 将基于账号信息生成适配内容
                  </p>
                </div>

                {/* Error text */}
                <div className="error-text">{accountError}</div>

                {/* Account rows */}
                <div
                  style={{
                    marginTop: 16,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 12,
                  }}
                >
                  {PLATFORMS.map((platform) => (
                    <div key={platform.name} className="account-row">
                      <div className="account-row-left">
                        <div
                          className="account-platform-icon"
                          style={{ background: platform.color }}
                        >
                          {platform.icon}
                        </div>
                        <span className="account-platform-name">{platform.name}</span>
                      </div>
                      <div className="account-row-right">
                        <select
                          className="account-select"
                          value={selectedAccounts[platform.name]}
                          onChange={(e) => handleAccountChange(platform.name, e.target.value)}
                        >
                          <option value="">请选择账号</option>
                          {platform.accounts.map((acc) => (
                            <option key={acc.value} value={acc.value}>
                              {acc.label}
                            </option>
                          ))}
                        </select>
                        <button
                          type="button"
                          className="account-settings-link"
                          onClick={(e) => e.preventDefault()}
                        >
                          设置
                        </button>
                      </div>
                    </div>
                  ))}
                </div>

                {/* 3-column grid: 领域定位 / 目标受众 / 风格调性 */}
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr 1fr',
                    gap: 16,
                    marginTop: 16,
                  }}
                >
                  {/* 领域定位 */}
                  <div>
                    <label
                      style={{
                        fontSize: 14,
                        color: '#4E5969',
                        display: 'block',
                        marginBottom: 4,
                      }}
                    >
                      领域定位
                    </label>
                    <select
                      className="input-field"
                      value={niche}
                      onChange={(e) => setNiche(e.target.value)}
                    >
                      <option value="">请选择</option>
                      {NICHE_OPTIONS.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                  </div>
                  {/* 目标受众 */}
                  <div>
                    <label
                      style={{
                        fontSize: 14,
                        color: '#4E5969',
                        display: 'block',
                        marginBottom: 4,
                      }}
                    >
                      目标受众
                    </label>
                    <select
                      className="input-field"
                      value={targetAudience}
                      onChange={(e) => setTargetAudience(e.target.value)}
                    >
                      <option value="">请选择</option>
                      {AUDIENCE_OPTIONS.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                  </div>
                  {/* 风格调性 */}
                  <div>
                    <label
                      style={{
                        fontSize: 14,
                        color: '#4E5969',
                        display: 'block',
                        marginBottom: 4,
                      }}
                    >
                      风格调性
                    </label>
                    <select
                      className="input-field"
                      value={tone}
                      onChange={(e) => setTone(e.target.value)}
                    >
                      <option value="">请选择</option>
                      {TONE_OPTIONS.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>

              {/* Divider */}
              <div style={{ height: 1, background: '#E5E6EB', marginBottom: 32 }} />

              {/* b. 发布设置 Section */}
              <div style={{ marginBottom: 32 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 10,
                    padding: '12px 16px',
                    borderRadius: 8,
                    background: '#F7F8FA',
                    border: '1px solid #E5E6EB',
                  }}
                >
                  <svg
                    className="flex-shrink-0"
                    width="16"
                    height="16"
                    fill="none"
                    stroke="#86909C"
                    viewBox="0 0 24 24"
                    strokeWidth="1.8"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="m11.25 11.25.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z"
                    />
                  </svg>
                  <div>
                    <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129' }}>发布设置</h2>
                    <p style={{ fontSize: 12, color: '#86909C', marginTop: 2 }}>
                      选择作品产出模式，发布完成后可一键下载到本地
                    </p>
                  </div>
                </div>

                {/* 发布模式选择：文字+封面 / 图文混排 / 全图卡片 */}
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr 1fr',
                    gap: 12,
                    marginTop: 12,
                  }}
                >
                  {[
                    { value: 'text-cover', name: '文字 + 封面', desc: '只生成文字与封面图，Token 最省' },
                    { value: 'image-text', name: '图文混排', desc: '文字 + 与上下文匹配的配图' },
                    { value: 'full-image', name: '全图卡片', desc: '整篇生成图片卡片，Token 消耗最大' },
                  ].map((m) => (
                    <div
                      key={m.value}
                      onClick={() => setPublishMode(m.value as PublishMode)}
                      style={{
                        cursor: 'pointer',
                        borderRadius: 8,
                        padding: '12px 14px',
                        border: publishMode === m.value ? '2px solid #165DFF' : '1px solid #E5E6EB',
                        background: publishMode === m.value ? 'rgba(22,93,255,0.04)' : '#fff',
                        transition: 'all 150ms ease',
                      }}
                    >
                      <div style={{ fontSize: 13, fontWeight: 600, color: '#1D2129' }}>
                        {m.name}
                      </div>
                      <div style={{ fontSize: 12, color: '#86909C', marginTop: 4 }}>{m.desc}</div>
                    </div>
                  ))}
                </div>
              </div>

              {/* 作品合集：创建时指定归入哪个合集（按类型区分） */}
              <div style={{ marginBottom: 32 }}>
                <div className="flex items-center gap-2">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#165DFF" strokeWidth="1.8">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z" />
                  </svg>
                  <div>
                    <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129' }}>作品合集</h2>
                    <p style={{ fontSize: 12, color: '#86909C', marginTop: 2 }}>
                      创建后自动把作品放入选中的合集（生成后也可在作品详情页调整）
                    </p>
                  </div>
                </div>

                {collectionsLoading ? (
                  <div className="mt-3 text-xs" style={{ color: '#C9CDD4' }}>加载合集…</div>
                ) : collections.length === 0 ? (
                  <div
                    className="mt-3 rounded-lg border px-4 py-3 text-xs"
                    style={{ borderColor: '#E5E6EB', color: '#86909C', background: '#FAFAFC' }}
                  >
                    还没有作品合集，可先跳过；去「作品合集」页面创建后，生成完成的作品也能随时归入。
                  </div>
                ) : (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {collections.map((c) => {
                      const selected = selectedCollectionIds.includes(c.collectionId)
                      return (
                        <button
                          key={c.collectionId}
                          onClick={() =>
                            setSelectedCollectionIds((prev) =>
                              selected
                                ? prev.filter((id) => id !== c.collectionId)
                                : [...prev, c.collectionId]
                            )
                          }
                          style={{
                            cursor: 'pointer',
                            borderRadius: 8,
                            padding: '8px 12px',
                            border: selected ? '2px solid #165DFF' : '1px solid #E5E6EB',
                            background: selected ? 'rgba(22,93,255,0.05)' : '#fff',
                            fontSize: 12,
                            color: '#1D2129',
                            transition: 'all 150ms ease',
                          }}
                        >
                          <span style={{ fontWeight: 600 }}>{c.name}</span>
                          <span style={{ marginLeft: 6, color: '#C40E3A' }}>{c.type}</span>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>

              {/* Divider */}
              <div style={{ height: 1, background: '#E5E6EB', marginBottom: 32 }} />

              {/* 热点选题：从热点监控模块直接取热点，一键生成作品 */}
              <div style={{ marginBottom: 32 }}>
                <div className="flex items-center gap-2">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#FF5C47" strokeWidth="1.8">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15.362 5.214A8.252 8.252 0 0 1 12 21 8.25 8.25 0 0 1 6.038 7.047 8.287 8.287 0 0 0 9 9.601a8.983 8.983 0 0 1 3.361-6.867 8.21 8.21 0 0 0 3 2.48Z" />
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 18a3.75 3.75 0 0 0 .495-7.468 5.99 5.99 0 0 0-1.925 3.547 5.975 5.975 0 0 1-2.133-1.001A3.75 3.75 0 0 0 12 18Z" />
                  </svg>
                  <div>
                    <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129' }}>热点选题</h2>
                    <p style={{ fontSize: 12, color: '#86909C', marginTop: 2 }}>
                      从多平台热榜选热点，点击「用此热点生成」直接创作；选题 Agent 也会自动参考热点
                    </p>
                  </div>
                </div>

                <div className="mt-3 flex flex-wrap gap-2">
                  {TREND_PLATFORMS.map((p) => (
                    <button
                      key={p.code}
                      onClick={() => setTrendPlatform(p.code)}
                      className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
                      style={{
                        background: trendPlatform === p.code ? '#FF5C47' : '#F2F3F5',
                        color: trendPlatform === p.code ? '#fff' : '#4E5969',
                        border: 'none',
                        cursor: 'pointer',
                      }}
                    >
                      {p.name}
                    </button>
                  ))}
                </div>

                {trendLoading ? (
                  <div className="mt-3 text-xs" style={{ color: '#C9CDD4' }}>加载热点…</div>
                ) : trendError ? (
                  <div className="mt-3 rounded-lg border px-4 py-3 text-xs" style={{ borderColor: '#F53F3F', color: '#F53F3F', background: '#FFF0F0' }}>
                    {trendError}
                  </div>
                ) : hotspots.length === 0 ? (
                  <div className="mt-3 rounded-lg border px-4 py-3 text-xs" style={{ borderColor: '#E5E6EB', color: '#86909C', background: '#FAFAFC' }}>
                    该平台暂无热点数据，可切换平台或稍后刷新
                  </div>
                ) : (
                  <div className="mt-3 space-y-2">
                    {hotspots.map((h) => (
                      <div
                        key={h.id}
                        className="flex items-center justify-between gap-3 rounded-lg border px-3 py-2.5 transition-all hover:shadow-sm"
                        style={{ borderColor: '#E5E6EB', background: '#fff' }}
                      >
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium" style={{ color: '#1D2129' }}>
                            {h.rank ? `${h.rank}. ` : ''}{h.title}
                          </div>
                          <div className="mt-0.5 text-xs" style={{ color: '#86909C' }}>
                            {h.category || ''}
                            {h.heat ? ` · 热度 ${h.heat}` : ''}
                          </div>
                        </div>
                        <button
                          onClick={() => handleHotspotCreate(h)}
                          disabled={submitting}
                          className="shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium text-white transition-colors"
                          style={{
                            background: submitting ? '#FFB8A6' : '#FF5C47',
                            border: 'none',
                            cursor: submitting ? 'not-allowed' : 'pointer',
                          }}
                        >
                          用此热点生成
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* c. 内容方向 Section */}
              <div style={{ marginBottom: 32 }}>
                <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129', marginBottom: 12 }}>
                  内容方向
                </h2>
                <div style={{ position: 'relative' }}>
                  <textarea
                    className="input-field"
                    placeholder="例如：如何克服拖延症、时间管理技巧..."
                    style={{ minHeight: 96, resize: 'vertical', fontFamily: 'inherit' }}
                    value={direction}
                    onChange={(e) => setDirection(e.target.value)}
                  />
                  <div
                    style={{
                      position: 'absolute',
                      bottom: 8,
                      right: 12,
                      fontSize: 12,
                      color: charColor,
                    }}
                  >
                    {charCount} / 500
                  </div>
                </div>
              </div>

              {/* Divider */}
              <div style={{ height: 1, background: '#E5E6EB', marginBottom: 32 }} />

              {/* d. 人工审核 Toggle */}
              <div>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <div>
                    <span style={{ fontSize: 14, fontWeight: 500, color: '#1D2129' }}>
                      人工审核
                    </span>
                    <p style={{ fontSize: 12, color: '#86909C', marginTop: 4 }}>
                      开启后每个阶段完成时会暂停等待你的审核确认
                    </p>
                  </div>
                  <div
                    className={`toggle-track ${humanReview ? 'active' : ''}`}
                    onClick={handleToggleReview}
                    onKeyDown={(e) => {
                      if (e.key === ' ' || e.key === 'Enter') {
                        e.preventDefault()
                        handleToggleReview()
                      }
                    }}
                    role="switch"
                    aria-checked={humanReview}
                    tabIndex={0}
                  >
                    <div className="toggle-thumb" />
                  </div>
                </div>
              </div>
            </div>
            {/* /Form Card */}

            {/* ===== Submit Section ===== */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'flex-end',
                gap: 16,
              }}
            >
              <button type="button" className="btn-outline" onClick={handleSaveDraft}>
                保存草稿
              </button>
              <button
                type="button"
                className="btn-outline"
                onClick={handleStartDiscussion}
                disabled={submitting || !direction.trim()}
                style={{
                  borderColor: '#165DFF',
                  color: '#165DFF',
                  opacity: submitting || !direction.trim() ? 0.5 : 1,
                  cursor: submitting || !direction.trim() ? 'not-allowed' : 'pointer',
                }}
              >
                讨论模式
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={handleSubmit}
                disabled={submitting}
              >
                {submitting ? '启动中...' : '启动工作流'}
              </button>
            </div>
            {submitError && (
              <div
                style={{
                  marginTop: 8,
                  padding: '8px 12px',
                  background: '#fff2f0',
                  border: '1px solid #ffccc7',
                  borderRadius: 6,
                  color: '#f53f3f',
                  fontSize: 13,
                }}
              >
                {submitError}
              </div>
            )}
          </div>
          {/* /LEFT COLUMN */}

          {/* ─────────── RIGHT COLUMN (Preview) ─────────── */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>

            {/* a. Pipeline Preview Card */}
            <div className="card" style={{ padding: 24 }}>
              <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129', marginBottom: 16 }}>
                流水线预览
              </h2>

              <div style={{ position: 'relative' }}>
                {PIPELINE_STAGES.map((stage, index) => (
                  <div
                    key={stage.name}
                    className="pipeline-stage"
                    style={
                      index === PIPELINE_STAGES.length - 1
                        ? { paddingBottom: 0 }
                        : undefined
                    }
                  >
                    <div className="pipeline-icon">{stage.icon}</div>
                    <div className="pipeline-info">
                      <span style={{ fontSize: 14, color: '#1D2129', fontWeight: 500 }}>
                        {stage.name}
                      </span>
                      <span
                        style={{
                          fontSize: 11,
                          color: '#86909C',
                          fontFamily: 'var(--font-mono)',
                        }}
                      >
                        {stage.port}
                      </span>
                      {stage.loop && (
                        <span
                          style={{ fontSize: 11, color: '#FF2D5E', marginLeft: 4 }}
                        >
                          {'\u21BA 循环回\u2460'}
                        </span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
            {/* /Pipeline Preview Card */}

            {/* b. Config Summary Card */}
            <div className="card" style={{ padding: 24 }}>
              <h2 style={{ fontSize: 14, fontWeight: 600, color: '#1D2129', marginBottom: 16 }}>
                配置预览
              </h2>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {/* Selected Accounts */}
                <div>
                  <div style={{ fontSize: 12, color: '#86909C', marginBottom: 8 }}>
                    关联账号
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                    {selectedAccountsList.length === 0 ? (
                      <span style={{ fontSize: 12, color: '#86909C' }}>未选择</span>
                    ) : (
                      selectedAccountsList.map((acc) => (
                        <span
                          key={acc.platform}
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 4,
                            padding: '4px 12px',
                            borderRadius: 8,
                            fontSize: 12,
                            fontWeight: 500,
                            background: acc.color,
                            color: '#fff',
                          }}
                        >
                          {acc.platform} {'\u00B7'} {acc.accountName}
                        </span>
                      ))
                    )}
                  </div>
                </div>

                {/* 领域定位 */}
                <div>
                  <div style={{ fontSize: 12, color: '#86909C', marginBottom: 4 }}>
                    领域定位
                  </div>
                  <div style={{ fontSize: 14, color: '#1D2129', fontWeight: 500 }}>
                    {niche ? nicheLabel : '--'}
                  </div>
                </div>

                {/* 目标受众 */}
                <div>
                  <div style={{ fontSize: 12, color: '#86909C', marginBottom: 4 }}>
                    目标受众
                  </div>
                  <div style={{ fontSize: 14, color: '#1D2129', fontWeight: 500 }}>
                    {targetAudience ? audienceLabel : '--'}
                  </div>
                </div>

                {/* 风格调性 */}
                <div>
                  <div style={{ fontSize: 12, color: '#86909C', marginBottom: 4 }}>
                    风格调性
                  </div>
                  <div style={{ fontSize: 14, color: '#1D2129', fontWeight: 500 }}>
                    {tone ? toneLabel : '--'}
                  </div>
                </div>

                {/* 内容方向 */}
                <div>
                  <div style={{ fontSize: 12, color: '#86909C', marginBottom: 4 }}>
                    内容方向
                  </div>
                  <div
                    style={{
                      fontSize: 13,
                      color: '#4E5969',
                      lineHeight: 1.6,
                      maxHeight: 80,
                      overflow: 'hidden',
                      wordBreak: 'break-all',
                    }}
                  >
                    {direction || '尚未填写'}
                  </div>
                </div>
              </div>
            </div>
            {/* /Config Summary Card */}

          </div>
          {/* /RIGHT COLUMN */}

        </div>
        {/* /Two-Column Layout */}
      </Layout>
    </>
  )
}
