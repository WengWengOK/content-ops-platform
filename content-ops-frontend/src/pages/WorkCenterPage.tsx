import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'

/* ────────────────────────────── Types & Mock Data ────────────────────────────── */

type WorkStatus = 'published' | 'draft' | 'generating'

interface Work {
  id: string
  title: string
  topic: string
  status: WorkStatus
  gradient: string
  hasCover: boolean
  date: string
  desc: string
}

const MOCK_WORKS: Work[] = [
  {
    id: 'work-001',
    title: '如何克服拖延症',
    topic: '拖延症',
    status: 'published',
    gradient: 'linear-gradient(135deg, #FF2D5E, #FF5C8A)',
    hasCover: true,
    date: '2026-07-23',
    desc: '从心理学角度分析拖延原因并提供实操方法论',
  },
  {
    id: 'work-002',
    title: '高效时间管理指南',
    topic: '时间管理',
    status: 'published',
    gradient: 'linear-gradient(135deg, #165DFF, #4080FF)',
    hasCover: true,
    date: '2026-07-22',
    desc: '职场人必备的时间管理技巧和工具推荐',
  },
  {
    id: 'work-003',
    title: '职场新人避坑指南',
    topic: '职场干货',
    status: 'draft',
    gradient: 'linear-gradient(135deg, #F77234, #FFAD66)',
    hasCover: false,
    date: '2026-07-22',
    desc: '新人入职前90天需要注意的关键事项',
  },
  {
    id: 'work-004',
    title: '读书笔记：《原子习惯》',
    topic: '读书笔记',
    status: 'published',
    gradient: 'linear-gradient(135deg, #7B61FF, #A78BFA)',
    hasCover: true,
    date: '2026-07-21',
    desc: '拆解习惯形成的四大定律并给出实践方案',
  },
  {
    id: 'work-005',
    title: '如何建立个人品牌',
    topic: '个人品牌',
    status: 'generating',
    gradient: 'linear-gradient(135deg, #14C9C9, #5CE0E0)',
    hasCover: false,
    date: '2026-07-21',
    desc: '从零开始打造个人影响力的完整路线图',
  },
  {
    id: 'work-006',
    title: '早起改变人生的秘密',
    topic: '自律养成',
    status: 'published',
    gradient: 'linear-gradient(135deg, #9FDB1D, #BEF264)',
    hasCover: true,
    date: '2026-07-20',
    desc: '科学解读早起习惯对身心健康的积极影响',
  },
  {
    id: 'work-007',
    title: '远程办公效率提升',
    topic: '效率工具',
    status: 'published',
    gradient: 'linear-gradient(135deg, #E8164A, #FF5C8A)',
    hasCover: true,
    date: '2026-07-19',
    desc: '远程工作者的效率提升策略和工具合集',
  },
  {
    id: 'work-008',
    title: '极简生活实践手册',
    topic: '生活方式',
    status: 'draft',
    gradient: 'linear-gradient(135deg, #F7BA1E, #FFD666)',
    hasCover: false,
    date: '2026-07-19',
    desc: '断舍离的实操指南和极简生活哲学',
  },
]

const STATUS_INFO: Record<WorkStatus, { label: string; cls: string }> = {
  published: { label: '已发布', cls: 'published' },
  draft: { label: '草稿', cls: 'draft' },
  generating: { label: '生成中', cls: 'generating' },
}

const TABS: { key: 'all' | WorkStatus; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'published', label: '已发布' },
  { key: 'draft', label: '草稿' },
  { key: 'generating', label: '生成中' },
]

/** First 6 characters of the title used as cover text. */
function getCoverText(title: string): string {
  return title.substring(0, 6)
}

/* ────────────────────────────── Component ─────────────────────────────── */

export function WorkCenterPage() {
  const [activeTab, setActiveTab] = useState<'all' | WorkStatus>('all')
  const [search, setSearch] = useState('')

  const filtered = MOCK_WORKS.filter((w) => {
    const matchTab = activeTab === 'all' || w.status === activeTab
    const keyword = search.trim().toLowerCase()
    const matchSearch = keyword === '' || w.title.toLowerCase().includes(keyword)
    return matchTab && matchSearch
  })

  // Stats computed from the works data (mirrors the design's updateStats())
  const stats = [
    {
      label: '全部作品',
      value: MOCK_WORKS.length,
      color: '#FF2D5E',
      pulse: false,
      sub: '平台全部作品',
    },
    {
      label: '已发布',
      value: MOCK_WORKS.filter((w) => w.status === 'published').length,
      color: '#00B42A',
      pulse: false,
      sub: '已上线作品',
    },
    {
      label: '草稿',
      value: MOCK_WORKS.filter((w) => w.status === 'draft').length,
      color: '#FF7D00',
      pulse: false,
      sub: '待发布作品',
    },
    {
      label: '生成中',
      value: MOCK_WORKS.filter((w) => w.status === 'generating').length,
      color: '#165DFF',
      pulse: true,
      sub: '正在生成作品',
    },
  ]

  return (
    <Layout activeNav="works" pageTitle="作品中心">
      {/* ───────────── 1. Stats Cards Row ───────────── */}
      <section
        className="grid grid-cols-4 gap-4 mb-8"
        style={{ animation: 'fadeInUp 400ms ease both' }}
        aria-label="作品统计概览"
      >
        {stats.map((stat) => (
          <div
            key={stat.label}
            className="card-hover bg-white rounded-lg border border-[#E5E6EB] p-5"
            role="button"
            tabIndex={0}
            aria-label={`${stat.label}: ${stat.value}`}
          >
            <div className="flex items-center gap-2 mb-3">
              <div
                className="w-2 h-2 rounded-full"
                style={{
                  background: stat.color,
                  ...(stat.pulse ? { animation: 'pulse-dot 2s ease-in-out infinite' } : {}),
                }}
              />
              <span className="text-sm font-medium" style={{ color: '#4E5969' }}>
                {stat.label}
              </span>
            </div>
            <div
              className="tabular-nums text-[32px] font-bold leading-none mb-1"
              style={{ color: '#1D2129' }}
            >
              {stat.value}
            </div>
            <div className="text-xs" style={{ color: '#86909C' }}>
              {stat.sub}
            </div>
          </div>
        ))}
      </section>

      {/* ───────────── 2. Work Gallery Section ───────────── */}
      <section
        className="mb-8"
        style={{ animation: 'fadeInUp 500ms ease both' }}
        aria-label="作品画廊"
      >
        {/* Section Header: Title + Search */}
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-[16px] font-semibold" style={{ color: '#1D2129' }}>
            全部作品
          </h2>
          <div className="relative" style={{ width: 280 }}>
            <svg
              className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4"
              style={{ color: '#86909C' }}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z"
              />
            </svg>
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索作品标题..."
              className="search-input w-full pl-9 pr-4 py-2 rounded-lg text-sm border border-[#E5E6EB] bg-white outline-none transition-colors"
              style={{ color: '#1D2129' }}
            />
          </div>
        </div>

        {/* Filter Tabs */}
        <div className="flex items-center gap-2 mb-6" role="tablist">
          {TABS.map((tab) => {
            const active = activeTab === tab.key
            return (
              <button
                key={tab.key}
                role="tab"
                aria-selected={active}
                onClick={() => setActiveTab(tab.key)}
                className={`filter-tab ${active ? 'active' : ''}`}
              >
                {tab.label}
              </button>
            )
          })}
        </div>

        {/* Work Cards Grid */}
        <div className="grid grid-cols-3 gap-4">
          {filtered.map((work) => {
            const statusInfo = STATUS_INFO[work.status]
            return (
              <Link key={work.id} to="/work-detail" className="work-card">
                {/* Cover area */}
                {work.hasCover ? (
                  <div
                    className="work-card-cover"
                    style={{ background: work.gradient }}
                  >
                    <svg
                      width="48"
                      height="48"
                      fill="none"
                      stroke="rgba(255,255,255,0.5)"
                      viewBox="0 0 24 24"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909M3.75 21h16.5A2.25 2.25 0 0 0 22.5 18.75V5.25A2.25 2.25 0 0 0 20.25 3H3.75A2.25 2.25 0 0 0 1.5 5.25v13.5A2.25 2.25 0 0 0 3.75 21Z"
                      />
                    </svg>
                  </div>
                ) : (
                  <div
                    className="work-card-cover"
                    style={{ background: work.gradient }}
                  >
                    <span className="work-card-cover-text">
                      {getCoverText(work.title)}
                    </span>
                  </div>
                )}

                {/* Content area */}
                <div style={{ padding: 16 }}>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      marginBottom: 8,
                    }}
                  >
                    <h3
                      style={{
                        fontSize: 14,
                        fontWeight: 600,
                        color: '#1D2129',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        flex: 1,
                        marginRight: 8,
                      }}
                    >
                      {work.title}
                    </h3>
                    <span className={`status-badge ${statusInfo.cls}`}>
                      {statusInfo.label}
                    </span>
                  </div>

                  <p
                    style={{
                      fontSize: 12,
                      color: '#86909C',
                      lineHeight: 1.5,
                      marginBottom: 12,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {work.desc}
                  </p>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span
                      style={{
                        fontSize: 11,
                        color: '#C9CDD4',
                        background: '#F2F3F5',
                        padding: '2px 8px',
                        borderRadius: 4,
                      }}
                    >
                      {work.topic}
                    </span>
                    <span
                      className="tabular-nums"
                      style={{ fontSize: 12, color: '#C9CDD4', marginLeft: 'auto' }}
                    >
                      {work.date}
                    </span>
                  </div>
                </div>
              </Link>
            )
          })}
        </div>

        {/* Empty State */}
        {filtered.length === 0 && (
          <div className="bg-white rounded-lg border border-[#E5E6EB] p-12 text-center">
            <div className="flex justify-center mb-6">
              <svg
                width="120"
                height="120"
                viewBox="0 0 120 120"
                fill="none"
                xmlns="http://www.w3.org/2000/svg"
              >
                <rect
                  x="30"
                  y="24"
                  width="60"
                  height="72"
                  rx="8"
                  stroke="#E5E6EB"
                  strokeWidth="2"
                  fill="#F7F8FA"
                />
                <rect x="42" y="40" width="36" height="4" rx="2" fill="#C9CDD4" />
                <rect x="42" y="52" width="28" height="4" rx="2" fill="#E5E6EB" />
                <rect x="42" y="64" width="32" height="4" rx="2" fill="#E5E6EB" />
                <circle cx="60" cy="86" r="4" fill="#FF2D5E" opacity="0.6" />
                <path
                  d="M80 90 L92 90 L92 78"
                  stroke="#165DFF"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  opacity="0.5"
                />
                <path
                  d="M80 90 L92 78"
                  stroke="#165DFF"
                  strokeWidth="2"
                  strokeLinecap="round"
                  opacity="0.5"
                />
              </svg>
            </div>
            <h3 className="text-[16px] font-semibold mb-2" style={{ color: '#1D2129' }}>
              没有找到匹配的作品
            </h3>
            <p className="text-sm" style={{ color: '#86909C' }}>
              试试调整筛选条件或搜索关键词
            </p>
          </div>
        )}
      </section>
    </Layout>
  )
}
