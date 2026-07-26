import { useState, useRef, useEffect, type ReactNode } from 'react'
import { Layout } from '@/components/layout/Layout'

/* ────────────────────────────────────────────────────────────
   Types
   ──────────────────────────────────────────────────────────── */
interface ToastItem {
  id: number
  message: string
  leaving: boolean
}

interface MetricChange {
  value: string
  direction: 'up' | 'down'
}

interface MetricItem {
  label: string
  value: string
  change?: MetricChange
}

interface PlatformDetail {
  key: string
  name: string
  color: string
  iconBg: string
  goLinkBg: string
  accountName: string
  accountId: string
  bindDate: string
  accountType: string
  verified: boolean
  followers: string
  lastActive: string
  metrics: MetricItem[]
  publishedCount: number
  draftCount: number
  avgReadTime: string
  platformUrl: string
  field: string
  audience: string
  style: string
  autoSync: boolean
  icon: ReactNode
}

interface OverviewStat {
  label: string
  value: string
  suffix?: string
  valueColor: string
  iconBg: string
  iconColor: string
  icon: ReactNode
  desc: ReactNode
  animationDelay: number
}

interface ModalFormState {
  platformName: string
  accountId: string
  nickname: string
  accountType: string
  field: string
  audience: string
  style: string
  remark: string
}

/* ────────────────────────────────────────────────────────────
   Inline SVG Icons — Overview
   ──────────────────────────────────────────────────────────── */
function LinkIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M13.19 8.688a4.5 4.5 0 0 1 1.242 7.244l-4.5 4.5a4.5 4.5 0 0 1-6.364-6.364l1.757-1.757m9.86-2.607a4.5 4.5 0 0 0-1.242-7.244l-4.5-4.5a4.5 4.5 0 0 0-6.364 6.364L4.343 7.657"
      />
    </svg>
  )
}

function UsersIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    </svg>
  )
}

function DocumentIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m5.231 13.481L15 17.25m-4.5-15H5.625c-.621 0-1.125.504-1.125 1.125v16.5c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Zm3.75 11.625a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    </svg>
  )
}

function ShieldCheckIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M9 12.75 11.25 15 15 9.75M21 12c0 1.268-.63 2.39-1.593 3.068a3.745 3.745 0 0 1-1.043 3.296 3.745 3.745 0 0 1-3.296 1.043A3.745 3.745 0 0 1 12 21c-1.268 0-2.39-.63-3.068-1.593a3.746 3.746 0 0 1-3.296-1.043 3.746 3.746 0 0 1-1.043-3.296A3.745 3.745 0 0 1 3 12c0-1.268.63-2.39 1.593-3.068a3.745 3.745 0 0 1 1.043-3.296 3.746 3.746 0 0 1 3.296-1.043A3.746 3.746 0 0 1 12 3c1.268 0 2.39.63 3.068 1.593a3.746 3.746 0 0 1 3.296 1.043 3.746 3.746 0 0 1 1.043 3.296A3.745 3.745 0 0 1 21 12Z"
      />
    </svg>
  )
}

/* ────────────────────────────────────────────────────────────
   Inline SVG Icons — Utility / Actions
   ──────────────────────────────────────────────────────────── */
function ArrowUpRight({ className = 'w-3.5 h-3.5', strokeWidth = 2.5 }: { className?: string; strokeWidth?: number }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={strokeWidth}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 19.5l15-15m0 0H8.25m11.25 0v11.25" />
    </svg>
  )
}

function ArrowDownLeft({ className = 'w-3 h-3', strokeWidth = 3 }: { className?: string; strokeWidth?: number }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={strokeWidth}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 4.5l-15 15m0 0h11.25m-11.25 0V8.25" />
    </svg>
  )
}

function ExternalArrowIcon() {
  return (
    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 19.5l15-15m0 0H8.25m11.25 0v11.25" />
    </svg>
  )
}

function ContentDocIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z"
      />
    </svg>
  )
}

function PencilIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10"
      />
    </svg>
  )
}

function ClockIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
    </svg>
  )
}

function EditBtnIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125"
      />
    </svg>
  )
}

function UnbindIcon() {
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M13.5 6H5.25A2.25 2.25 0 0 0 3 8.25v10.5A2.25 2.25 0 0 0 5.25 21h10.5A2.25 2.25 0 0 0 18 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25"
      />
    </svg>
  )
}

function CloseIcon() {
  return (
    <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
    </svg>
  )
}

function InfoIcon() {
  return (
    <svg className="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="m11.25 11.25.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z"
      />
    </svg>
  )
}

/* ────────────────────────────────────────────────────────────
   Inline SVG Icons — Platform icons
   ──────────────────────────────────────────────────────────── */
function XhsIcon() {
  return (
    <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
      <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" />
    </svg>
  )
}

function WechatIcon() {
  return (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.076-4.076a1.526 1.526 0 0 1 1.037-.443 48.282 48.282 0 0 0 5.68-.494c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"
      />
    </svg>
  )
}

function DouyinIcon() {
  return (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347a1.125 1.125 0 0 1-1.667-.986V5.653Z"
      />
    </svg>
  )
}

function BiliIcon() {
  return (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M9.879 7.519c1.171-1.025 3.071-1.025 4.242 0 1.172 1.025 1.172 2.687 0 3.712-.203.179-.43.326-.67.442-.745.361-1.45.999-1.45 1.827v.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 5.25h.008v.008H12v-.008Z"
      />
    </svg>
  )
}

/* ────────────────────────────────────────────────────────────
   Data — Overview statistics
   ──────────────────────────────────────────────────────────── */
const OVERVIEW_STATS: OverviewStat[] = [
  {
    label: '已绑定平台',
    value: '4',
    suffix: '个',
    valueColor: '#1D2129',
    iconBg: '#E8F3FF',
    iconColor: '#165DFF',
    icon: <LinkIcon />,
    desc: <span style={{ color: '#86909C' }}>小红书 / 公众号 / 抖音 / 哔哩哔哩</span>,
    animationDelay: 400,
  },
  {
    label: '总粉丝数',
    value: '12,580',
    valueColor: '#1D2129',
    iconBg: '#FFF0F5',
    iconColor: '#FF2D5E',
    icon: <UsersIcon />,
    desc: (
      <span className="flex items-center gap-1">
        <ArrowUpRight className="w-3.5 h-3.5" strokeWidth={2.5} />
        <span className="text-xs font-medium" style={{ color: '#00B42A' }}>+280 本周新增</span>
      </span>
    ),
    animationDelay: 480,
  },
  {
    label: '总作品数',
    value: '32',
    suffix: '篇',
    valueColor: '#1D2129',
    iconBg: '#F7F0FF',
    iconColor: '#7B61FF',
    icon: <DocumentIcon />,
    desc: (
      <span className="flex items-center gap-1">
        <ArrowUpRight className="w-3.5 h-3.5" strokeWidth={2.5} />
        <span className="text-xs font-medium" style={{ color: '#00B42A' }}>+3 本周发布</span>
      </span>
    ),
    animationDelay: 560,
  },
  {
    label: '账号健康度',
    value: '优秀',
    valueColor: '#00B42A',
    iconBg: '#E8F8F0',
    iconColor: '#00B42A',
    icon: <ShieldCheckIcon />,
    desc: <span style={{ color: '#86909C' }}>所有账号运行正常</span>,
    animationDelay: 640,
  },
]

/* ────────────────────────────────────────────────────────────
   Data — Platform details
   ──────────────────────────────────────────────────────────── */
const PLATFORMS: PlatformDetail[] = [
  {
    key: 'xhs',
    name: '小红书',
    color: '#FF2D5E',
    iconBg: 'rgba(255,45,94,0.08)',
    goLinkBg: 'rgba(255,45,94,0.06)',
    accountName: '@成长日记',
    accountId: 'XHS_20250315',
    bindDate: '2025-03-15',
    accountType: '个人号',
    verified: true,
    followers: '8,520',
    lastActive: '2026-07-25',
    metrics: [
      { label: '曝光量', value: '28,450', change: { value: '+12.3%', direction: 'up' } },
      { label: '互动量', value: '2,777', change: { value: '+8.7%', direction: 'up' } },
      { label: '点赞数', value: '2,187' },
      { label: '评论数', value: '412' },
      { label: '收藏数', value: '890' },
      { label: '转发数', value: '178' },
    ],
    publishedCount: 14,
    draftCount: 2,
    avgReadTime: '2分18秒',
    platformUrl: 'https://creator.xiaohongshu.com',
    field: '生活',
    audience: '25-34岁',
    style: '轻松活泼',
    autoSync: true,
    icon: <XhsIcon />,
  },
  {
    key: 'gh',
    name: '公众号',
    color: '#165DFF',
    iconBg: 'rgba(22,93,255,0.08)',
    goLinkBg: 'rgba(22,93,255,0.06)',
    accountName: '@干货分享站',
    accountId: 'WX_GH_20250401',
    bindDate: '2025-04-01',
    accountType: '订阅号',
    verified: true,
    followers: '2,890',
    lastActive: '2026-07-24',
    metrics: [
      { label: '曝光量', value: '18,920', change: { value: '+5.6%', direction: 'up' } },
      { label: '互动量', value: '1,658', change: { value: '+3.2%', direction: 'up' } },
      { label: '点赞数', value: '1,345' },
      { label: '评论数', value: '215' },
      { label: '收藏数', value: '420' },
      { label: '转发数', value: '98' },
    ],
    publishedCount: 10,
    draftCount: 1,
    avgReadTime: '3分42秒',
    platformUrl: 'https://mp.weixin.qq.com',
    field: '教育',
    audience: '25-34岁',
    style: '干货实用',
    autoSync: true,
    icon: <WechatIcon />,
  },
  {
    key: 'dy',
    name: '抖音',
    color: '#FE2C55',
    iconBg: 'rgba(254,44,85,0.08)',
    goLinkBg: 'rgba(254,44,85,0.06)',
    accountName: '@短视频日记',
    accountId: 'DY_20250510',
    bindDate: '2025-05-10',
    accountType: '个人号',
    verified: false,
    followers: '3,200',
    lastActive: '2026-07-23',
    metrics: [
      { label: '曝光量', value: '12,800', change: { value: '-2.1%', direction: 'down' } },
      { label: '互动量', value: '1,560', change: { value: '+1.5%', direction: 'up' } },
      { label: '点赞数', value: '980' },
      { label: '评论数', value: '245' },
      { label: '收藏数', value: '380' },
      { label: '转发数', value: '89' },
    ],
    publishedCount: 8,
    draftCount: 2,
    avgReadTime: '45秒',
    platformUrl: 'https://creator.douyin.com',
    field: '职场',
    audience: '35-44岁',
    style: '专业严谨',
    autoSync: true,
    icon: <DouyinIcon />,
  },
  {
    key: 'bl',
    name: '哔哩哔哩',
    color: '#00A1D6',
    iconBg: 'rgba(0,161,214,0.08)',
    goLinkBg: 'rgba(0,161,214,0.06)',
    accountName: '@二次元笔记',
    accountId: 'BL_20250620',
    bindDate: '2025-06-20',
    accountType: '个人号',
    verified: false,
    followers: '1,560',
    lastActive: '2026-07-22',
    metrics: [
      { label: '曝光量', value: '8,450', change: { value: '+15.8%', direction: 'up' } },
      { label: '互动量', value: '620', change: { value: '+9.3%', direction: 'up' } },
      { label: '点赞数', value: '485' },
      { label: '评论数', value: '92' },
      { label: '收藏数', value: '128' },
      { label: '转发数', value: '36' },
    ],
    publishedCount: 6,
    draftCount: 1,
    avgReadTime: '3分28秒',
    platformUrl: 'https://member.bilibili.com',
    field: '科技',
    audience: '18-24岁',
    style: '专业严谨',
    autoSync: true,
    icon: <BiliIcon />,
  },
]

/* ────────────────────────────────────────────────────────────
   Modal select options
   ──────────────────────────────────────────────────────────── */
const ACCOUNT_TYPES = ['个人号', '企业号', '订阅号', '服务号']
const FIELD_OPTIONS = ['科技', '教育', '生活', '美食', '旅行', '健康', '文化', '职场', '金融', '其他']
const AUDIENCE_OPTIONS = ['18-24岁', '25-34岁', '35-44岁', '45岁以上', '不限']
const STYLE_OPTIONS = ['专业严谨', '轻松活泼', '温暖治愈', '幽默风趣', '干货实用']

/* ────────────────────────────────────────────────────────────
   Shared inline style objects
   ──────────────────────────────────────────────────────────── */
const editableInputStyle: React.CSSProperties = {
  border: '1px solid #E5E6EB',
  borderRadius: 8,
  padding: '10px 14px',
  fontSize: 14,
  width: '100%',
  outline: 'none',
  transition: 'border-color 0.2s',
  fontFamily: 'inherit',
  color: '#1D2129',
  boxSizing: 'border-box',
}

const readonlyInputStyle: React.CSSProperties = {
  border: '1px solid #E5E6EB',
  borderRadius: 8,
  padding: '10px 14px',
  fontSize: 14,
  width: '100%',
  outline: 'none',
  background: '#F7F8FA',
  color: '#86909C',
  cursor: 'not-allowed',
  fontFamily: 'inherit',
  boxSizing: 'border-box',
}

const selectStyle: React.CSSProperties = {
  border: '1px solid #E5E6EB',
  borderRadius: 8,
  padding: '10px 14px',
  fontSize: 14,
  width: '100%',
  outline: 'none',
  transition: 'border-color 0.2s',
  fontFamily: 'inherit',
  color: '#1D2129',
  background: '#FFFFFF',
  boxSizing: 'border-box',
  appearance: 'auto',
  cursor: 'pointer',
}

const fieldLabelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 13,
  color: '#4E5969',
  marginBottom: 6,
  fontWeight: 500,
}

const handleFocus = (e: React.FocusEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
  e.currentTarget.style.borderColor = '#165DFF'
}
const handleBlur = (e: React.FocusEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
  e.currentTarget.style.borderColor = '#E5E6EB'
}

/* ────────────────────────────────────────────────────────────
   Main component
   ──────────────────────────────────────────────────────────── */
export function PlatformAccountsPage() {
  /* ── Toast state ── */
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const toastIdRef = useRef(0)

  /* ── Modal state ── */
  const [modalOpen, setModalOpen] = useState(false)
  const [modalTitle, setModalTitle] = useState('编辑账号')
  const [form, setForm] = useState<ModalFormState>({
    platformName: '',
    accountId: '',
    nickname: '',
    accountType: '个人号',
    field: '科技',
    audience: '25-34岁',
    style: '专业严谨',
    remark: '',
  })
  const [autoSync, setAutoSync] = useState(true)

  /* ── Toast helpers ── */
  const showToast = (message: string) => {
    toastIdRef.current += 1
    const id = toastIdRef.current
    setToasts((prev) => [...prev, { id, message, leaving: false }])
    window.setTimeout(() => {
      setToasts((prev) => prev.map((t) => (t.id === id ? { ...t, leaving: true } : t)))
      window.setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id))
      }, 250)
    }, 2500)
  }

  /* ── Modal helpers ── */
  const openEditModal = (platform: PlatformDetail) => {
    setModalTitle(`编辑 ${platform.name} 账号`)
    setForm({
      platformName: platform.name,
      accountId: platform.accountId,
      nickname: platform.accountName,
      accountType: platform.accountType,
      field: platform.field,
      audience: platform.audience,
      style: platform.style,
      remark: '',
    })
    setAutoSync(platform.autoSync)
    setModalOpen(true)
  }

  const closeEditModal = () => {
    setModalOpen(false)
  }

  const saveEditModal = () => {
    if (!form.nickname.trim()) {
      showToast('账号昵称不能为空')
      return
    }
    const title = modalTitle
    closeEditModal()
    showToast(`${title} - 修改已保存成功`)
  }

  const handleOverlayClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      closeEditModal()
    }
  }

  const updateForm = <K extends keyof ModalFormState>(key: K, value: ModalFormState[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  /* ── Lock body scroll while modal is open ── */
  useEffect(() => {
    if (modalOpen) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [modalOpen])

  return (
    <Layout
      activeNav="settings"
      showBackButton
      backHref="/user-profile"
      pageTitle="平台账号详情"
    >
      <div>
        {/* ═══════════════════ 1. 概览统计卡片 ═══════════════════ */}
        <section
          className="grid grid-cols-4 gap-4 mb-6"
          style={{ animation: 'fadeInUp 400ms ease both' }}
          aria-label="账号概览"
        >
          {OVERVIEW_STATS.map((stat) => (
            <div
              key={stat.label}
              className="card-hover bg-white rounded-xl border border-[#E5E6EB] p-5"
              style={{ animation: `fadeInUp ${stat.animationDelay}ms ease both` }}
            >
              <div className="flex items-center justify-between mb-3">
                <div className="text-xs font-medium" style={{ color: '#86909C' }}>
                  {stat.label}
                </div>
                <div
                  className="w-8 h-8 rounded-lg flex items-center justify-center"
                  style={{ background: stat.iconBg, color: stat.iconColor }}
                >
                  {stat.icon}
                </div>
              </div>
              <div
                className="stat-number text-2xl font-bold mb-1"
                style={{ color: stat.valueColor }}
              >
                {stat.value}
                {stat.suffix && (
                  <span className="text-sm font-normal" style={{ color: '#86909C' }}>
                    {' '}
                    {stat.suffix}
                  </span>
                )}
              </div>
              <div className="text-xs">{stat.desc}</div>
            </div>
          ))}
        </section>

        {/* ═══════════════════ 2. 平台详情卡片 ═══════════════════ */}
        <div>
          {PLATFORMS.map((platform, index) => {
            const verifiedColor = platform.verified ? '#00B42A' : platform.color
            return (
              <div
                key={platform.key}
                className="card-hover platform-card bg-white rounded-xl border border-[#E5E6EB] p-6 mb-5"
                style={{ animation: `fadeInUp ${500 + index * 100}ms ease both` }}
                aria-label={`${platform.name}账号详情`}
              >
                {/* left color bar */}
                <span
                  style={{
                    position: 'absolute',
                    left: 0,
                    top: 0,
                    bottom: 0,
                    width: 4,
                    borderRadius: '12px 0 0 12px',
                    background: platform.color,
                  }}
                />

                {/* ── Card Header ── */}
                <div className="flex items-center justify-between mb-5">
                  <div className="flex items-center gap-3">
                    <div
                      className="w-10 h-10 rounded-xl flex items-center justify-center"
                      style={{ background: platform.iconBg, color: platform.color }}
                    >
                      {platform.icon}
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-base font-semibold" style={{ color: '#1D2129' }}>
                          {platform.name}
                        </span>
                        <span className="text-sm" style={{ color: '#86909C' }}>
                          {platform.accountName}
                        </span>
                        <span
                          className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium"
                          style={{ background: platform.iconBg, color: platform.color }}
                        >
                          已绑定
                        </span>
                      </div>
                    </div>
                  </div>
                  <a
                    href={platform.platformUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="platform-go-link"
                    style={{ background: platform.goLinkBg, color: platform.color }}
                  >
                    前往平台
                    <ExternalArrowIcon />
                  </a>
                </div>

                {/* ── 基本信息 ── */}
                <div className="mb-5">
                  <div
                    className="text-xs font-semibold uppercase tracking-wider mb-3"
                    style={{ color: '#86909C' }}
                  >
                    基本信息
                  </div>
                  <div className="info-grid">
                    <div className="info-item">
                      <span className="info-label">账号 ID</span>
                      <span
                        className="info-value"
                        style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 13 }}
                      >
                        {platform.accountId}
                      </span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">绑定时间</span>
                      <span className="info-value">{platform.bindDate}</span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">账号类型</span>
                      <span className="info-value">{platform.accountType}</span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">认证状态</span>
                      <span className="info-value" style={{ color: verifiedColor }}>
                        {platform.verified ? '已认证' : '未认证'}
                      </span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">粉丝数</span>
                      <span className="info-value">{platform.followers}</span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">最近活跃</span>
                      <span className="info-value">{platform.lastActive}</span>
                    </div>
                  </div>
                </div>

                {/* ── 数据表现 ── */}
                <div className="mb-5">
                  <div
                    className="text-xs font-semibold uppercase tracking-wider mb-3"
                    style={{ color: '#86909C' }}
                  >
                    数据表现
                  </div>
                  <div className="metrics-grid">
                    {platform.metrics.map((metric) => (
                      <div key={metric.label} className="metric-item">
                        <span className="metric-label">{metric.label}</span>
                        <span className="metric-value">{metric.value}</span>
                        {metric.change && (
                          <span
                            className={`metric-change ${metric.change.direction === 'up' ? 'up' : 'down'}`}
                          >
                            {metric.change.direction === 'up' ? (
                              <ArrowUpRight className="w-3 h-3" strokeWidth={3} />
                            ) : (
                              <ArrowDownLeft className="w-3 h-3" strokeWidth={3} />
                            )}
                            {metric.change.value}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>

                {/* ── 内容数据 ── */}
                <div className="flex items-center gap-6 mb-5 py-3 border-t border-b border-[#F2F3F5]">
                  <div className="flex items-center gap-2" style={{ color: '#86909C' }}>
                    <ContentDocIcon />
                    <span className="text-sm" style={{ color: '#4E5969' }}>
                      已发布 <strong style={{ color: '#1D2129' }}>{platform.publishedCount}</strong> 篇
                    </span>
                  </div>
                  <div className="flex items-center gap-2" style={{ color: '#86909C' }}>
                    <PencilIcon />
                    <span className="text-sm" style={{ color: '#4E5969' }}>
                      草稿 <strong style={{ color: '#1D2129' }}>{platform.draftCount}</strong> 篇
                    </span>
                  </div>
                  <div className="flex items-center gap-2" style={{ color: '#86909C' }}>
                    <ClockIcon />
                    <span className="text-sm" style={{ color: '#4E5969' }}>
                      平均阅读 <strong style={{ color: '#1D2129' }}>{platform.avgReadTime}</strong>
                    </span>
                  </div>
                </div>

                {/* ── 操作按钮 ── */}
                <div className="flex items-center justify-end gap-3">
                  <button
                    type="button"
                    className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium transition-all"
                    style={{ background: '#165DFF', color: '#FFFFFF' }}
                    onClick={() => openEditModal(platform)}
                  >
                    <EditBtnIcon />
                    编辑账号信息
                  </button>
                  <button
                    type="button"
                    className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium transition-all"
                    style={{ border: '1px solid #F53F3F', color: '#F53F3F', background: 'transparent' }}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.background = '#FFECE8'
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.background = 'transparent'
                    }}
                    onClick={() => showToast(`请确认是否解绑${platform.name}账号`)}
                  >
                    <UnbindIcon />
                    解绑账号
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* ═══════════════════ Edit Modal ═══════════════════ */}
      {modalOpen && (
        <div
          onClick={handleOverlayClick}
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.4)',
            zIndex: 10000,
            backdropFilter: 'blur(4px)',
            display: 'flex',
            alignItems: 'flex-start',
            justifyContent: 'center',
            fontFamily: "'Inter', 'PingFang SC', 'Microsoft YaHei', sans-serif",
          }}
        >
          <div
            style={{
              background: '#FFFFFF',
              borderRadius: 16,
              maxWidth: 640,
              width: '92%',
              margin: '80px 0 0 0',
              padding: 0,
              overflow: 'hidden',
              boxShadow: '0 20px 60px rgba(0,0,0,0.15)',
            }}
          >
            {/* Modal Header */}
            <div
              style={{
                padding: '24px 32px',
                borderBottom: '1px solid #E5E6EB',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <h3 style={{ margin: 0, fontSize: 18, fontWeight: 600, color: '#1D2129' }}>
                {modalTitle}
              </h3>
              <button
                type="button"
                onClick={closeEditModal}
                style={{
                  background: 'none',
                  border: 'none',
                  cursor: 'pointer',
                  padding: 4,
                  color: '#86909C',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRadius: 8,
                  transition: 'background 0.2s',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = '#F2F3F5'
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'none'
                }}
              >
                <CloseIcon />
              </button>
            </div>

            {/* Modal Body */}
            <div style={{ padding: '24px 32px', maxHeight: '60vh', overflowY: 'auto' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
                {/* 平台名称 (只读) */}
                <div>
                  <label style={fieldLabelStyle}>平台名称</label>
                  <input
                    type="text"
                    readOnly
                    value={form.platformName}
                    style={readonlyInputStyle}
                    onChange={() => {}}
                  />
                </div>
                {/* 账号 ID (只读) */}
                <div>
                  <label style={fieldLabelStyle}>账号 ID</label>
                  <input
                    type="text"
                    readOnly
                    value={form.accountId}
                    style={readonlyInputStyle}
                    onChange={() => {}}
                  />
                </div>
                {/* 账号昵称 */}
                <div>
                  <label style={fieldLabelStyle}>账号昵称</label>
                  <input
                    type="text"
                    value={form.nickname}
                    style={editableInputStyle}
                    onFocus={handleFocus}
                    onBlur={handleBlur}
                    onChange={(e) => updateForm('nickname', e.target.value)}
                  />
                </div>
                {/* 账号类型 */}
                <div>
                  <label style={fieldLabelStyle}>账号类型</label>
                  <select
                    value={form.accountType}
                    style={selectStyle}
                    onFocus={handleFocus}
                    onBlur={handleBlur}
                    onChange={(e) => updateForm('accountType', e.target.value)}
                  >
                    {ACCOUNT_TYPES.map((opt) => (
                      <option key={opt} value={opt}>
                        {opt}
                      </option>
                    ))}
                  </select>
                </div>
                {/* 领域定位 */}
                <div>
                  <label style={fieldLabelStyle}>领域定位</label>
                  <select
                    value={form.field}
                    style={selectStyle}
                    onFocus={handleFocus}
                    onBlur={handleBlur}
                    onChange={(e) => updateForm('field', e.target.value)}
                  >
                    {FIELD_OPTIONS.map((opt) => (
                      <option key={opt} value={opt}>
                        {opt}
                      </option>
                    ))}
                  </select>
                </div>
                {/* 目标受众 */}
                <div>
                  <label style={fieldLabelStyle}>目标受众</label>
                  <select
                    value={form.audience}
                    style={selectStyle}
                    onFocus={handleFocus}
                    onBlur={handleBlur}
                    onChange={(e) => updateForm('audience', e.target.value)}
                  >
                    {AUDIENCE_OPTIONS.map((opt) => (
                      <option key={opt} value={opt}>
                        {opt}
                      </option>
                    ))}
                  </select>
                </div>
                {/* 风格调性 */}
                <div>
                  <label style={fieldLabelStyle}>风格调性</label>
                  <select
                    value={form.style}
                    style={selectStyle}
                    onFocus={handleFocus}
                    onBlur={handleBlur}
                    onChange={(e) => updateForm('style', e.target.value)}
                  >
                    {STYLE_OPTIONS.map((opt) => (
                      <option key={opt} value={opt}>
                        {opt}
                      </option>
                    ))}
                  </select>
                </div>
                {/* 自动同步数据 (Toggle) */}
                <div>
                  <label style={fieldLabelStyle}>自动同步数据</label>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, paddingTop: 4 }}>
                    <button
                      type="button"
                      onClick={() => setAutoSync((v) => !v)}
                      style={{
                        position: 'relative',
                        display: 'inline-block',
                        width: 44,
                        height: 24,
                        flexShrink: 0,
                        cursor: 'pointer',
                        border: 'none',
                        padding: 0,
                        background: 'transparent',
                      }}
                      aria-pressed={autoSync}
                      aria-label="自动同步数据"
                    >
                      <span
                        style={{
                          position: 'absolute',
                          inset: 0,
                          background: autoSync ? '#165DFF' : '#C9CDD4',
                          borderRadius: 12,
                          transition: 'background 0.2s',
                        }}
                      />
                      <span
                        style={{
                          position: 'absolute',
                          top: 2,
                          left: autoSync ? 22 : 2,
                          width: 20,
                          height: 20,
                          background: '#FFFFFF',
                          borderRadius: '50%',
                          transition: 'left 0.2s',
                          boxShadow: '0 1px 3px rgba(0,0,0,0.15)',
                        }}
                      />
                    </button>
                    <span style={{ fontSize: 13, color: '#4E5969' }}>
                      {autoSync ? '已开启' : '已关闭'}
                    </span>
                  </div>
                </div>
              </div>
              {/* 备注 (全宽) */}
              <div style={{ marginTop: 20 }}>
                <label style={fieldLabelStyle}>备注</label>
                <textarea
                  rows={3}
                  placeholder="请输入备注信息..."
                  value={form.remark}
                  style={{
                    ...editableInputStyle,
                    resize: 'vertical',
                    minHeight: 80,
                  }}
                  onFocus={handleFocus}
                  onBlur={handleBlur}
                  onChange={(e) => updateForm('remark', e.target.value)}
                />
              </div>
            </div>

            {/* Modal Footer */}
            <div
              style={{
                padding: '16px 32px',
                borderTop: '1px solid #E5E6EB',
                display: 'flex',
                justifyContent: 'flex-end',
                gap: 12,
              }}
            >
              <button
                type="button"
                onClick={closeEditModal}
                style={{
                  padding: '8px 20px',
                  borderRadius: 8,
                  fontSize: 14,
                  fontWeight: 500,
                  border: '1px solid #E5E6EB',
                  background: '#FFFFFF',
                  color: '#4E5969',
                  cursor: 'pointer',
                  transition: 'all 0.2s',
                  fontFamily: 'inherit',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = '#F7F8FA'
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = '#FFFFFF'
                }}
              >
                取消
              </button>
              <button
                type="button"
                onClick={saveEditModal}
                style={{
                  padding: '8px 20px',
                  borderRadius: 8,
                  fontSize: 14,
                  fontWeight: 500,
                  border: 'none',
                  background: '#165DFF',
                  color: '#FFFFFF',
                  cursor: 'pointer',
                  transition: 'all 0.2s',
                  fontFamily: 'inherit',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = '#4080FF'
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = '#165DFF'
                }}
              >
                保存修改
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════════════ Toast Container ═══════════════════ */}
      {toasts.length > 0 && (
        <div
          style={{
            position: 'fixed',
            top: 80,
            right: 24,
            zIndex: 9999,
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
          }}
        >
          {toasts.map((t) => (
            <div
              key={t.id}
              style={{
                padding: '12px 20px',
                borderRadius: 10,
                background: '#FFFFFF',
                border: '1px solid #E5E6EB',
                boxShadow: '0 8px 24px rgba(0,0,0,0.08)',
                fontSize: 13,
                color: '#1D2129',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                animation: t.leaving ? 'none' : 'fadeInUp 0.3s ease both',
                opacity: t.leaving ? 0 : 1,
                transform: t.leaving ? 'translateY(-8px) scale(0.96)' : 'none',
                transition: 'opacity 0.25s ease, transform 0.25s ease',
              }}
            >
              <span style={{ color: '#165DFF', display: 'flex', flexShrink: 0 }}>
                <InfoIcon />
              </span>
              <span>{t.message}</span>
            </div>
          ))}
        </div>
      )}
    </Layout>
  )
}
