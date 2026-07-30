import { Link } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import { LoadingView, ErrorView, EmptyView } from '@/components/common/StateViews'
import { useDashboardData } from '@/hooks/useWorkflow'
import type { DashboardWorkflow, DashboardActivity } from '@/hooks/useWorkflow'
import { timeAgo } from '@/utils/timeFormat'

/* ────────────────────────────── Status Styling ────────────────────────────── */

interface BadgeStyle {
  color: string
  bg: string
}

const STATUS_BADGE: Record<string, BadgeStyle> = {
  PENDING: { color: '#86909C', bg: '#F2F3F5' },
  IN_PROGRESS: { color: '#165DFF', bg: '#E8F3FF' },
  AWAITING_HUMAN: { color: '#FF7D00', bg: '#FFF7E8' },
  AWAITING_ASYNC: { color: '#FF7D00', bg: '#FFF7E8' },
  COMPLETED: { color: '#00B42A', bg: '#E8F8F0' },
  FAILED: { color: '#F53F3F', bg: '#FFECE8' },
  SKIPPED: { color: '#C9CDD4', bg: '#F7F8FA' },
}

const STAGE_BADGE: Record<string, BadgeStyle> = {
  'topic-planning': { color: '#E8164A', bg: '#FFF0F5' },
  'content-creation': { color: '#E8164A', bg: '#FFF0F5' },
  'image-design': { color: '#722ED1', bg: '#F5F0FF' },
  'publishing': { color: '#0FC6C2', bg: '#E8FFFB' },
  'data-analysis': { color: '#3491FA', bg: '#E8F7FF' },
  'optimization': { color: '#00B42A', bg: '#E8F8F0' },
}

const PROGRESS_COLOR: Record<string, string> = {
  PENDING: '#C9CDD4',
  IN_PROGRESS: '#165DFF',
  AWAITING_HUMAN: '#FF7D00',
  AWAITING_ASYNC: '#FF7D00',
  COMPLETED: '#00B42A',
  FAILED: '#F53F3F',
  SKIPPED: '#C9CDD4',
}

interface StatItem {
  label: string
  value: number
  color: string
  hint: string
}

/* ────────────────────────────── Components ────────────────────────────── */

function StatsRow({ stats }: { stats: { running: number; awaitingReview: number; completed: number; total: number } }) {
  const items: StatItem[] = [
    { label: '运行中工作流', value: stats.running, color: '#165DFF', hint: '正在执行的工作流' },
    { label: '待审核', value: stats.awaitingReview, color: '#FF7D00', hint: '需要人工审核' },
    { label: '已完成', value: stats.completed, color: '#00B42A', hint: '已发布内容' },
    { label: '工作流总数', value: stats.total, color: '#FF2D5E', hint: '全部工作流' },
  ]

  return (
    <section
      className="grid grid-cols-4 gap-4 mb-8 animate-fadeInUp"
      style={{ animationDelay: '400ms' }}
      aria-label="数据统计概览"
    >
      {items.map((stat) => (
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
              style={{ background: stat.color }}
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
            {stat.hint}
          </div>
        </div>
      ))}
    </section>
  )
}

function WorkflowCard({ wf }: { wf: DashboardWorkflow }) {
  const statusBadge = STATUS_BADGE[wf.status] || STATUS_BADGE.PENDING
  const stageBadge = STAGE_BADGE[wf.stageCode] || { color: '#86909C', bg: '#F2F3F5' }
  const progressColor = PROGRESS_COLOR[wf.status] || '#C9CDD4'

  return (
    <Link
      key={wf.workflowId}
      to={`/workflow-detail?workflowId=${wf.workflowId}`}
      className="card-hover bg-white rounded-lg border border-[#E5E6EB] p-5 block"
    >
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-3">
          <h3 className="text-[14px] font-semibold" style={{ color: '#1D2129' }}>
            {wf.title}
          </h3>
          <span
            className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
            style={{ color: statusBadge.color, background: statusBadge.bg }}
          >
            {wf.statusLabel}
          </span>
        </div>
        <span className="text-xs" style={{ color: '#86909C' }}>
          {timeAgo(wf.updatedAt || wf.createdAt)}
        </span>
      </div>

      <div className="flex items-center gap-2 mb-4">
        <span className="text-xs" style={{ color: '#86909C' }}>
          当前阶段:
        </span>
        <span
          className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
          style={{ color: stageBadge.color, background: stageBadge.bg }}
        >
          {wf.stageLabel}
        </span>
        <span className="text-xs tabular-nums" style={{ color: '#86909C' }}>
          进度 {wf.progress}%
        </span>
      </div>

      <div className="progress-track mb-4">
        <div
          className="progress-fill"
          style={{ width: `${wf.progress}%`, background: progressColor }}
        />
      </div>

      <div className="flex items-center justify-end">
        <span
          className="inline-flex items-center gap-1 text-sm font-medium transition-all duration-200 hover:underline"
          style={{ color: '#165DFF', textDecoration: 'none' }}
        >
          查看详情
          <svg
            className="w-4 h-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
            strokeWidth={2}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="m8.25 4.5 7.5 7.5-7.5 7.5"
            />
          </svg>
        </span>
      </div>
    </Link>
  )
}

function ActivityTimeline({ activities }: { activities: DashboardActivity[] }) {
  return (
    <section
      className="bg-white rounded-lg border border-[#E5E6EB] p-6 animate-fadeInUp"
      style={{ animationDelay: '600ms' }}
      aria-label="最近动态"
    >
      <h2 className="text-[16px] font-semibold mb-6" style={{ color: '#1D2129' }}>
        最近动态
      </h2>

      {activities.length === 0 ? (
        <p className="text-sm" style={{ color: '#86909C' }}>
          暂无动态
        </p>
      ) : (
        <div className="relative">
          <div
            className="absolute left-[3px] top-1 bottom-1 w-px rounded-full"
            style={{ background: '#E5E6EB' }}
          />
          <div className="space-y-6">
            {activities.map((item, i) => (
              <div key={i} className="relative flex items-start gap-4 pl-5">
                <div
                  className={`absolute left-0 top-1 w-2 h-2 rounded-full flex-shrink-0 ${
                    item.pulse ? 'animate-pulse-dot' : ''
                  }`}
                  style={{ background: item.color }}
                />
                <div className="flex-1 flex items-center justify-between">
                  <div>
                    <span className="text-sm font-medium" style={{ color: '#1D2129' }}>
                      {item.title}
                    </span>
                    <p className="text-xs mt-1" style={{ color: '#86909C' }}>
                      {item.desc}
                    </p>
                  </div>
                  <span
                    className="text-xs flex-shrink-0 ml-4 tabular-nums"
                    style={{ color: '#86909C' }}
                  >
                    {timeAgo(item.time)}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}

/* ────────────────────────────── Main Component ────────────────────────────── */

export function Dashboard() {
  const { workflows, stats, activities, loading, error, refresh } = useDashboardData()

  return (
    <Layout activeNav="dashboard" pageTitle="工作流仪表盘">
      {loading ? (
        <LoadingView text="加载工作流数据..." />
      ) : error ? (
        <ErrorView
          message={error}
          onRetry={refresh}
        />
      ) : workflows.length === 0 ? (
        <>
          <StatsRow stats={stats} />
          <EmptyView
            title="暂无工作流"
            description="创建你的第一个内容运营工作流，开始自动化内容生产"
            actionLabel="新建工作流"
            actionLink="/create-workflow"
          />
        </>
      ) : (
        <>
          {/* Stats Cards */}
          <StatsRow stats={stats} />

          {/* Workflow List */}
          <section
            className="mb-8 animate-fadeInUp"
            style={{ animationDelay: '500ms' }}
            aria-label="我的工作流列表"
          >
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-[16px] font-semibold" style={{ color: '#1D2129' }}>
                我的工作流
              </h2>
              <Link
                to="/create-workflow"
                className="btn-primary inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white"
              >
                <svg
                  className="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                  strokeWidth={2.5}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M12 4.5v15m7.5-7.5h-15"
                  />
                </svg>
                新建工作流
              </Link>
            </div>

            <div className="space-y-4">
              {workflows.map((wf) => (
                <WorkflowCard key={wf.workflowId} wf={wf} />
              ))}
            </div>
          </section>

          {/* Recent Activity */}
          <ActivityTimeline activities={activities} />
        </>
      )}
    </Layout>
  )
}
