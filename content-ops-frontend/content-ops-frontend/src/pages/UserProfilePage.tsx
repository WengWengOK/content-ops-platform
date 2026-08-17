import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'

/* ────────────────────────────────────────────────────────────
   Platform accounts data
   ──────────────────────────────────────────────────────────── */
interface PlatformAccount {
  key: string
  name: string
  accountName: string
  accountId: string
  bindDate: string
  followers: string
  lastUpdate: string
  color: string
  iconBg: string
  hoverColor: string
  goLink: string
  goBorder: string
  goBg: string
  icon: React.ReactNode
}

const PLATFORM_ACCOUNTS: PlatformAccount[] = [
  {
    key: 'xiaohongshu',
    name: '小红书',
    accountName: '@成长日记',
    accountId: 'XHS_20250315',
    bindDate: '2025-03-15',
    followers: '8,520',
    lastUpdate: '2026-07-25',
    color: '#FF2D5E',
    iconBg: '#FFF0F5',
    hoverColor: '#E8164A',
    goLink: 'https://creator.xiaohongshu.com',
    goBorder: '#FFD6E7',
    goBg: '#FFF0F5',
    icon: (
      <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 0 0 8.716-6.747M12 21a9.004 9.004 0 0 1-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 0 1 7.843 4.582M12 3a8.997 8.997 0 0 0-7.843 4.582" />
      </svg>
    ),
  },
  {
    key: 'wechat',
    name: '公众号',
    accountName: '@干货分享站',
    accountId: 'WX_GH_20250401',
    bindDate: '2025-04-01',
    followers: '2,890',
    lastUpdate: '2026-07-24',
    color: '#165DFF',
    iconBg: '#E8F3FF',
    hoverColor: '#4080FF',
    goLink: 'https://mp.weixin.qq.com',
    goBorder: '#B8D4FF',
    goBg: '#E8F3FF',
    icon: (
      <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z" />
      </svg>
    ),
  },
  {
    key: 'douyin',
    name: '抖音',
    accountName: '@短视频日记',
    accountId: 'DY_20250510',
    bindDate: '2025-05-10',
    followers: '3,200',
    lastUpdate: '2026-07-23',
    color: '#FE2C55',
    iconBg: '#FFF0F3',
    hoverColor: '#FF5C7E',
    goLink: 'https://creator.douyin.com',
    goBorder: '#FFD0DB',
    goBg: '#FFF0F3',
    icon: (
      <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347a1.125 1.125 0 0 1-1.667-.986V5.653Z" />
      </svg>
    ),
  },
  {
    key: 'bilibili',
    name: '哔哩哔哩',
    accountName: '@二次元笔记',
    accountId: 'BL_20250620',
    bindDate: '2025-06-20',
    followers: '1,560',
    lastUpdate: '2026-07-22',
    color: '#00A1D6',
    iconBg: '#E8F8FF',
    hoverColor: '#33B5E5',
    goLink: 'https://member.bilibili.com',
    goBorder: '#99D9EA',
    goBg: '#E8F8FF',
    icon: (
      <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347a1.125 1.125 0 0 1-1.667-.986V5.653Z" />
      </svg>
    ),
  },
]

/* ────────────────────────────────────────────────────────────
   Security settings data
   ──────────────────────────────────────────────────────────── */
interface SecurityItem {
  title: string
  desc: string
  badge?: string
  badgeColor?: string
  badgeBg?: string
  icon: React.ReactNode
  iconBg: string
  iconColor: string
  buttonText: string
}

const SECURITY_ITEMS: SecurityItem[] = [
  {
    title: '登录设备管理',
    desc: '当前设备：MacBook Pro · 上次登录：2026-07-25 10:30',
    iconBg: '#E8F3FF',
    iconColor: '#165DFF',
    buttonText: '管理',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 17.25v1.007a3 3 0 0 1-.879 2.122L7.5 21h9l-.621-.621A3 3 0 0 1 15 18.257V17.25m6-12V15a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 15V5.25A2.25 2.25 0 0 1 5.25 3h13.5A2.25 2.25 0 0 1 21 5.25Z" />
      </svg>
    ),
  },
  {
    title: '两步验证',
    desc: '使用手机验证码进行二次验证',
    badge: '已开启',
    badgeColor: '#00B42A',
    badgeBg: '#E8F8F0',
    iconBg: '#E8F8F0',
    iconColor: '#00B42A',
    buttonText: '设置',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75 11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285Z" />
      </svg>
    ),
  },
  {
    title: 'API 密钥管理',
    desc: '管理用于第三方集成的 API 密钥',
    iconBg: '#FFF7E8',
    iconColor: '#FF7D00',
    buttonText: '管理',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
        <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 5.25a3 3 0 0 1 3 3m3 0a6 6 0 0 1-7.029 5.912c-.563-.097-1.159.026-1.563.43L10.5 17.25H8.25v2.25H6v2.25H2.25v-2.818c0-.597.237-1.17.659-1.591l6.499-6.499c.404-.404.527-1 .43-1.563A6 6 0 1 1 21.75 8.25Z" />
      </svg>
    ),
  },
]

const INDUSTRY_OPTIONS = [
  { value: '互联网', label: '互联网' },
  { value: '教育', label: '教育' },
  { value: '金融', label: '金融' },
  { value: '医疗', label: '医疗' },
  { value: '文化', label: '文化' },
  { value: '其他', label: '其他' },
]

/* ────────────────────────────────────────────────────────────
   Helper: Platform Detail Link (dynamic hover color)
   ──────────────────────────────────────────────────────────── */
function PlatformDetailLink({
  to,
  bgColor,
  hoverColor,
  children,
}: {
  to: string
  bgColor: string
  hoverColor: string
  children: React.ReactNode
}) {
  const [hovered, setHovered] = useState(false)
  return (
    <Link
      to={to}
      className="inline-flex items-center px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors no-underline"
      style={{ background: hovered ? hoverColor : bgColor, color: '#FFFFFF' }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {children}
    </Link>
  )
}

/* ────────────────────────────────────────────────────────────
   Main component
   ──────────────────────────────────────────────────────────── */
export function UserProfilePage() {
  const [modalOpen, setModalOpen] = useState(false)
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null)

  // Edit profile form state
  const [form, setForm] = useState({
    nickname: '张小明',
    email: 'zhangxiaoming@example.com',
    phone: '138****8888',
    bio: '专注内容创作，分享成长经验',
    industry: '互联网',
    website: '',
  })

  const handleFormChange = (field: keyof typeof form, value: string) => {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => {
      const result = ev.target?.result
      if (typeof result === 'string') {
        setAvatarPreview(result)
      }
    }
    reader.readAsDataURL(file)
  }

  const handleSave = () => {
    setModalOpen(false)
  }

  return (
    <Layout activeNav="settings" pageTitle="设置">
      <div className="space-y-6">
        {/* ─────────── 1. 用户基本信息卡片 ─────────── */}
        <section
          className="card p-6 mb-6"
          style={{ animation: 'fadeInUp 400ms ease both' }}
          aria-label="用户基本信息"
        >
          <div className="flex items-start justify-between">
            {/* Left: Avatar + Info */}
            <div className="flex items-start gap-5">
              {/* Avatar 80x80 */}
              <div
                className="w-20 h-20 rounded-full flex items-center justify-center text-white text-2xl font-bold flex-shrink-0"
                style={{ background: 'linear-gradient(135deg, #FF2D5E, #FF5C8A)' }}
              >
                U
              </div>
              {/* Info */}
              <div className="flex flex-col gap-3">
                {/* Name */}
                <div className="flex items-center gap-3">
                  <h2 className="text-xl font-bold" style={{ color: '#1D2129' }}>
                    张小明
                  </h2>
                </div>
                {/* Info row */}
                <div className="flex items-center gap-6 text-sm" style={{ color: '#4E5969' }}>
                  <div className="flex items-center gap-2">
                    <svg
                      className="w-4 h-4"
                      style={{ color: '#86909C' }}
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      strokeWidth="1.8"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"
                      />
                    </svg>
                    <span>zhangxiaoming@example.com</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <svg
                      className="w-4 h-4"
                      style={{ color: '#86909C' }}
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      strokeWidth="1.8"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M10.5 1.5H8.25A2.25 2.25 0 0 0 6 3.75v16.5a2.25 2.25 0 0 0 2.25 2.25h7.5A2.25 2.25 0 0 0 18 20.25V3.75a2.25 2.25 0 0 0-2.25-2.25H13.5m-3 0V3h3V1.5m-3 0h3m-3 18.75h3"
                      />
                    </svg>
                    <span>138****8888</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <svg
                      className="w-4 h-4"
                      style={{ color: '#86909C' }}
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      strokeWidth="1.8"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 7.5v11.25m-18 0A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75m-18 0v-7.5A2.25 2.25 0 0 1 5.25 9h13.5A2.25 2.25 0 0 1 21 11.25v7.5"
                      />
                    </svg>
                    <span>注册时间：2025-03-15</span>
                  </div>
                </div>
                {/* Tags Row */}
                <div className="flex items-center gap-2">
                  <span
                    className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium"
                    style={{ background: '#E8F3FF', color: '#165DFF' }}
                  >
                    内容创作者
                  </span>
                  <span
                    className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium"
                    style={{ background: 'linear-gradient(135deg, #FFD6E7, #FFF0F5)', color: '#FF2D5E' }}
                  >
                    Pro
                  </span>
                </div>
              </div>
            </div>

            {/* Right: Action Buttons */}
            <div className="flex items-center gap-3 flex-shrink-0">
              <button
                type="button"
                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer bg-[#F2F3F5] hover:bg-[#E5E6EB]"
                style={{ color: '#4E5969', border: '1px solid #E5E6EB' }}
                onClick={() => setModalOpen(true)}
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10"
                  />
                </svg>
                编辑资料
              </button>
              <button
                type="button"
                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer bg-white border border-[#E5E6EB] text-[#4E5969] hover:border-[#FF2D5E] hover:text-[#FF2D5E]"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z"
                  />
                </svg>
                修改密码
              </button>
            </div>
          </div>
        </section>

        {/* ─────────── 2. 平台账号管理 ─────────── */}
        <section className="mb-6" style={{ animation: 'fadeInUp 500ms ease both' }} aria-label="平台账号管理">
          {/* Section Header */}
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className="text-base font-semibold" style={{ color: '#1D2129' }}>
                平台账号管理
              </h3>
              <p className="text-xs mt-1" style={{ color: '#86909C' }}>
                管理已绑定的各平台账号，可查看详情或解绑
              </p>
            </div>
          </div>

          {/* Platform Cards List */}
          <div className="space-y-4">
            {PLATFORM_ACCOUNTS.map((platform) => (
              <div
                key={platform.key}
                className="platform-card bg-white rounded-xl border border-[#E5E6EB] p-5 transition-shadow duration-200 hover:shadow-[0_4px_16px_rgba(0,0,0,0.06)]"
              >
                {/* Left 4px color bar */}
                <div
                  style={{
                    position: 'absolute',
                    left: 0,
                    top: 0,
                    bottom: 0,
                    width: '4px',
                    borderRadius: '12px 0 0 12px',
                    background: platform.color,
                  }}
                />
                <div className="flex items-center justify-between">
                  {/* Left: Platform Info */}
                  <div className="flex items-center gap-5">
                    {/* Platform Icon 48x48 */}
                    <div
                      className="w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0"
                      style={{ background: platform.iconBg, color: platform.color }}
                    >
                      {platform.icon}
                    </div>
                    {/* Info Grid 5 cols */}
                    <div className="grid grid-cols-5 gap-x-10 gap-y-2 text-sm">
                      <div>
                        <div className="text-xs mb-1" style={{ color: '#86909C' }}>
                          平台
                        </div>
                        <div className="font-medium flex items-center gap-2" style={{ color: '#1D2129' }}>
                          {platform.name}
                          <span className="text-xs" style={{ color: '#86909C' }}>
                            {platform.accountName}
                          </span>
                        </div>
                      </div>
                      <div>
                        <div className="text-xs mb-1" style={{ color: '#86909C' }}>
                          账号 ID
                        </div>
                        <div className="font-medium" style={{ color: '#4E5969' }}>
                          {platform.accountId}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs mb-1" style={{ color: '#86909C' }}>
                          绑定时间
                        </div>
                        <div style={{ color: '#4E5969' }}>{platform.bindDate}</div>
                      </div>
                      <div>
                        <div className="text-xs mb-1" style={{ color: '#86909C' }}>
                          粉丝数
                        </div>
                        <div className="font-medium tabular-nums" style={{ color: '#1D2129' }}>
                          {platform.followers}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs mb-1" style={{ color: '#86909C' }}>
                          最近更新
                        </div>
                        <div style={{ color: '#4E5969' }}>{platform.lastUpdate}</div>
                      </div>
                    </div>
                  </div>

                  {/* Right: Status + Actions */}
                  <div className="flex items-center gap-3 flex-shrink-0">
                    {/* Status Tag */}
                    <span
                      className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium"
                      style={{ background: '#E8F8F0', color: '#00B42A' }}
                    >
                      已绑定
                    </span>
                    {/* 查看详情 - platform colored link */}
                    <PlatformDetailLink
                      to="/platform-accounts"
                      bgColor={platform.color}
                      hoverColor={platform.hoverColor}
                    >
                      查看详情
                    </PlatformDetailLink>
                    {/* 解绑 button */}
                    <button
                      type="button"
                      className="inline-flex items-center px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer bg-white hover:bg-[#FFECE8]"
                      style={{ color: '#F53F3F', border: '1px solid #F53F3F' }}
                    >
                      解绑
                    </button>
                    {/* 前往平台 - external link */}
                    <a
                      href={platform.goLink}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="platform-go-link inline-flex items-center gap-1 px-3.5 py-1.5 rounded-lg text-xs font-medium no-underline transition-all hover:opacity-[0.85]"
                      style={{
                        color: platform.color,
                        border: `1px solid ${platform.goBorder}`,
                        background: platform.goBg,
                      }}
                    >
                      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2">
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M13.5 6H5.25A2.25 2.25 0 0 0 3 8.25v10.5A2.25 2.25 0 0 0 5.25 21h10.5A2.25 2.25 0 0 0 18 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25"
                        />
                      </svg>
                      前往平台
                    </a>
                  </div>
                </div>
              </div>
            ))}

            {/* ===== Add New Platform Card ===== */}
            <button
              type="button"
              className="w-full flex flex-col items-center justify-center gap-2 rounded-xl cursor-pointer transition-all border-2 border-dashed border-[#C9CDD4] bg-white hover:border-[#FF2D5E] hover:bg-[#FFF0F5]"
              style={{ minHeight: '120px' }}
            >
              <svg className="w-8 h-8" style={{ color: '#C9CDD4' }} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v6m3-3H9m12 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
              </svg>
              <span className="text-sm font-medium" style={{ color: '#86909C' }}>
                绑定新平台
              </span>
            </button>
          </div>
        </section>

        {/* ─────────── 3. 账号安全设置 ─────────── */}
        <section
          className="card mb-6"
          style={{ animation: 'fadeInUp 600ms ease both' }}
          aria-label="账号安全设置"
        >
          {/* Section Header (inside card) */}
          <div className="flex items-center justify-between px-5 pt-5 pb-0">
            <div>
              <h3 className="text-base font-semibold" style={{ color: '#1D2129' }}>
                账号安全设置
              </h3>
            </div>
          </div>

          {/* Security Items */}
          <div className="px-5 pb-5 pt-3">
            {SECURITY_ITEMS.map((item, index) => (
              <div
                key={item.title}
                className="flex items-center justify-between py-4 px-5"
                style={{
                  borderBottom:
                    index < SECURITY_ITEMS.length - 1 ? '1px solid #F2F3F5' : 'none',
                }}
              >
                <div className="flex items-center gap-4">
                  {/* Icon 40x40 */}
                  <div
                    className="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0"
                    style={{ background: item.iconBg, color: item.iconColor }}
                  >
                    {item.icon}
                  </div>
                  <div>
                    <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
                      {item.title}
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      {item.badge && (
                        <span
                          className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                          style={{ background: item.badgeBg, color: item.badgeColor }}
                        >
                          {item.badge}
                        </span>
                      )}
                      <span className="text-xs" style={{ color: '#86909C' }}>
                        {item.desc}
                      </span>
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  className="inline-flex items-center px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer bg-[#F2F3F5] hover:bg-[#E5E6EB]"
                  style={{ color: '#4E5969' }}
                >
                  {item.buttonText}
                </button>
              </div>
            ))}
          </div>
        </section>
      </div>

      {/* ─────────── Edit Profile Modal ─────────── */}
      {modalOpen && (
        <div
          className="fixed inset-0 z-[10000] flex items-center justify-center"
          style={{ background: 'rgba(0, 0, 0, 0.4)' }}
          onClick={() => setModalOpen(false)}
        >
          <div
            className="bg-white rounded-2xl w-[90%] max-w-[560px] max-h-[90vh] overflow-y-auto custom-scrollbar"
            style={{ padding: '32px', boxShadow: '0 12px 40px rgba(0,0,0,0.12)' }}
            onClick={(e) => e.stopPropagation()}
          >
            {/* Modal Title */}
            <div className="text-lg font-semibold mb-6" style={{ color: '#1D2129' }}>
              编辑资料
            </div>

            {/* Avatar Upload Area */}
            <div className="flex items-center gap-4 mb-6">
              <div
                className="w-16 h-16 rounded-full flex items-center justify-center text-white text-2xl font-bold flex-shrink-0 overflow-hidden"
                style={{ background: 'linear-gradient(135deg, #FF2D5E, #FF5C8A)' }}
              >
                {avatarPreview ? (
                  <img src={avatarPreview} alt="头像预览" className="w-full h-full object-cover" />
                ) : (
                  'U'
                )}
              </div>
              <div className="flex flex-col gap-1.5">
                <label
                  className="inline-flex items-center gap-1 px-3.5 py-1.5 rounded-lg text-xs font-medium cursor-pointer transition-all"
                  style={{ border: '1px solid #E5E6EB', background: '#F2F3F5', color: '#4E5969' }}
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M6.827 6.175A2.31 2.31 0 0 1 5.186 7.23c-.38.054-.757.112-1.134.175C2.999 7.58 2.25 8.507 2.25 9.574V18a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9.574c0-1.067-.75-1.994-1.802-2.169a47.865 47.865 0 0 0-1.134-.175 2.31 2.31 0 0 1-1.64-1.055l-.822-1.316a2.192 2.192 0 0 0-1.736-1.039 48.774 48.774 0 0 0-5.232 0 2.192 2.192 0 0 0-1.736 1.039l-.821 1.316Z"
                    />
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M16.5 12.75a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0Z"
                    />
                  </svg>
                  更换头像
                  <input
                    type="file"
                    accept="image/*"
                    style={{ display: 'none' }}
                    onChange={handleAvatarChange}
                  />
                </label>
                <span className="text-xs" style={{ color: '#86909C' }}>
                  支持 JPG、PNG 格式，建议尺寸 200x200
                </span>
              </div>
            </div>

            {/* Form Fields */}
            <div className="space-y-[18px]">
              {/* Nickname */}
              <div>
                <label
                  className="block text-[13px] font-medium mb-1.5"
                  style={{ color: '#4E5969' }}
                >
                  昵称
                </label>
                <input
                  type="text"
                  className="w-full border border-[#E5E6EB] rounded-lg px-3 py-2 text-sm text-[#1D2129] bg-white outline-none box-border transition-colors focus:border-[#FF2D5E] focus:shadow-[0_0_0_2px_rgba(255,45,94,0.1)]"
                  value={form.nickname}
                  placeholder="请输入昵称"
                  onChange={(e) => handleFormChange('nickname', e.target.value)}
                />
              </div>

              {/* Email */}
              <div>
                <label
                  className="block text-[13px] font-medium mb-1.5"
                  style={{ color: '#4E5969' }}
                >
                  邮箱
                </label>
                <input
                  type="email"
                  className="w-full border border-[#E5E6EB] rounded-lg px-3 py-2 text-sm text-[#1D2129] bg-white outline-none box-border transition-colors focus:border-[#FF2D5E] focus:shadow-[0_0_0_2px_rgba(255,45,94,0.1)]"
                  value={form.email}
                  placeholder="请输入邮箱"
                  onChange={(e) => handleFormChange('email', e.target.value)}
                />
              </div>

              {/* Phone */}
              <div>
                <label
                  className="block text-[13px] font-medium mb-1.5"
                  style={{ color: '#4E5969' }}
                >
                  手机号
                </label>
                <input
                  type="text"
                  className="w-full border border-[#E5E6EB] rounded-lg px-3 py-2 text-sm text-[#1D2129] bg-white outline-none box-border transition-colors focus:border-[#FF2D5E] focus:shadow-[0_0_0_2px_rgba(255,45,94,0.1)]"
                  value={form.phone}
                  placeholder="请输入手机号"
                  onChange={(e) => handleFormChange('phone', e.target.value)}
                />
              </div>

              {/* Bio */}
              <div>
                <label
                  className="block text-[13px] font-medium mb-1.5"
                  style={{ color: '#4E5969' }}
                >
                  个人简介
                </label>
                <textarea
                  className="w-full border border-[#E5E6EB] rounded-lg px-3 py-2 text-sm text-[#1D2129] bg-white outline-none box-border transition-colors focus:border-[#FF2D5E] focus:shadow-[0_0_0_2px_rgba(255,45,94,0.1)]"
                  style={{ resize: 'vertical', minHeight: '80px' }}
                  value={form.bio}
                  placeholder="请输入个人简介"
                  onChange={(e) => handleFormChange('bio', e.target.value)}
                />
              </div>

              {/* Industry */}
              <div>
                <label
                  className="block text-[13px] font-medium mb-1.5"
                  style={{ color: '#4E5969' }}
                >
                  所在行业
                </label>
                <select
                  className="w-full border border-[#E5E6EB] rounded-lg px-3 py-2 text-sm text-[#1D2129] bg-white outline-none box-border transition-colors cursor-pointer appearance-auto focus:border-[#FF2D5E] focus:shadow-[0_0_0_2px_rgba(255,45,94,0.1)]"
                  value={form.industry}
                  onChange={(e) => handleFormChange('industry', e.target.value)}
                >
                  {INDUSTRY_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>

              {/* Website (optional) */}
              <div>
                <label
                  className="block text-[13px] font-medium mb-1.5"
                  style={{ color: '#4E5969' }}
                >
                  个人网站 <span style={{ color: '#86909C', fontWeight: 400 }}>（选填）</span>
                </label>
                <input
                  type="url"
                  className="w-full border border-[#E5E6EB] rounded-lg px-3 py-2 text-sm text-[#1D2129] bg-white outline-none box-border transition-colors focus:border-[#FF2D5E] focus:shadow-[0_0_0_2px_rgba(255,45,94,0.1)]"
                  value={form.website}
                  placeholder="https://yourwebsite.com"
                  onChange={(e) => handleFormChange('website', e.target.value)}
                />
              </div>
            </div>

            {/* Footer Buttons */}
            <div
              className="flex justify-end gap-3 mt-7 pt-5"
              style={{ borderTop: '1px solid #F2F3F5' }}
            >
              <button
                type="button"
                className="px-6 py-2 rounded-lg text-sm font-medium cursor-pointer transition-colors"
                style={{ border: '1px solid #E5E6EB', background: '#F2F3F5', color: '#4E5969' }}
                onClick={() => setModalOpen(false)}
              >
                取消
              </button>
              <button
                type="button"
                className="px-6 py-2 rounded-lg text-sm font-medium cursor-pointer transition-colors border-none text-white"
                style={{ background: '#FF2D5E' }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = '#E8164A'
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = '#FF2D5E'
                }}
                onClick={handleSave}
              >
                保存修改
              </button>
            </div>
          </div>
        </div>
      )}
    </Layout>
  )
}
