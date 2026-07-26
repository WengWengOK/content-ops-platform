import { Link } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'

/* ────────────────────────────── Mock Data ────────────────────────────── */

interface StatItem {
  label: string
  value: number
  color: string
  hint: string
}

const stats: StatItem[] = [
  { label: '运行中工作流', value: 3, color: '#165DFF', hint: '正在执行的工作流' },
  { label: '待审核', value: 2, color: '#FF7D00', hint: '需要人工审核' },
  { label: '已完成', value: 12, color: '#00B42A', hint: '已发布内容' },
  { label: '本周期内容', value: 8, color: '#FF2D5E', hint: '本周产出内容' },
]

interface Badge {
  label: string
  color: string
  bg: string
}

interface Workflow {
  title: string
  status: Badge
  stage: Badge
  progress: number
  progressColor: string
  timeAgo: string
}

const workflows: Workflow[] = [
  {
    title: '个人成长选题',
    status: { label: '运行中', color: '#165DFF', bg: '#E8F3FF' },
    stage: { label: '内容创作', color: '#E8164A', bg: '#FFF0F5' },
    progress: 33,
    progressColor: '#165DFF',
    timeAgo: '2小时前',
  },
  {
    title: '职场干货合集',
    status: { label: '待审核', color: '#FF7D00', bg: '#FFF7E8' },
    stage: { label: '选题策划', color: '#E56E00', bg: '#FFF7E8' },
    progress: 17,
    progressColor: '#FF7D00',
    timeAgo: '5小时前',
  },
  {
    title: '读书笔记系列',
    status: { label: '已完成', color: '#00B42A', bg: '#E8F8F0' },
    stage: { label: '优化迭代', color: '#009A24', bg: '#E8F8F0' },
    progress: 100,
    progressColor: '#00B42A',
    timeAgo: '1天前',
  },
]

interface Activity {
  title: string
  desc: string
  time: string
  color: string
  pulse: boolean
}

const activities: Activity[] = [
  {
    title: '内容创作进行中',
    desc: '「个人成长选题」正在生成内容草稿',
    time: '30分钟前',
    color: '#165DFF',
    pulse: true,
  },
  {
    title: '选题策划完成',
    desc: '「职场干货合集」选题已通过审核',
    time: '1小时前',
    color: '#00B42A',
    pulse: false,
  },
  {
    title: '工作流已启动',
    desc: '「个人成长选题」工作流开始执行',
    time: '2小时前',
    color: '#FF2D5E',
    pulse: false,
  },
  {
    title: '数据分析报告生成',
    desc: '「读书笔记系列」数据分析报告已生成',
    time: '1天前',
    color: '#C9CDD4',
    pulse: false,
  },
]

/* ────────────────────────────── Component ─────────────────────────────── */

export function Dashboard() {
  return (
    <Layout activeNav="dashboard" pageTitle="工作流仪表盘">
      {/* ───────────── Stats Cards Row ───────────── */}
      <section
        className="grid grid-cols-4 gap-4 mb-8 animate-fadeInUp"
        style={{ animationDelay: '400ms' }}
        aria-label="数据统计概览"
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

      {/* ───────────── Workflow List ───────────── */}
      <section
        className="mb-8 animate-fadeInUp"
        style={{ animationDelay: '500ms' }}
        aria-label="我的工作流列表"
      >
        {/* Section Header */}
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

        {/* Workflow Cards */}
        <div className="space-y-4">
          {workflows.map((wf) => (
            <Link
              key={wf.title}
              to="/workflow-detail"
              className="card-hover bg-white rounded-lg border border-[#E5E6EB] p-5 block"
            >
              {/* title + status badge + time */}
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-3">
                  <h3 className="text-[14px] font-semibold" style={{ color: '#1D2129' }}>
                    {wf.title}
                  </h3>
                  <span
                    className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                    style={{ color: wf.status.color, background: wf.status.bg }}
                  >
                    {wf.status.label}
                  </span>
                </div>
                <span className="text-xs" style={{ color: '#86909C' }}>
                  {wf.timeAgo}
                </span>
              </div>

              {/* current stage + progress on the SAME line */}
              <div className="flex items-center gap-2 mb-4">
                <span className="text-xs" style={{ color: '#86909C' }}>
                  当前阶段:
                </span>
                <span
                  className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
                  style={{ color: wf.stage.color, background: wf.stage.bg }}
                >
                  {wf.stage.label}
                </span>
                <span className="text-xs tabular-nums" style={{ color: '#86909C' }}>
                  进度 {wf.progress}%
                </span>
              </div>

              {/* progress bar */}
              <div className="progress-track mb-4">
                <div
                  className="progress-fill"
                  style={{ width: `${wf.progress}%`, background: wf.progressColor }}
                />
              </div>

              {/* view detail link */}
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
          ))}
        </div>
      </section>

      {/* ───────────── Recent Activity Timeline ───────────── */}
      <section
        className="bg-white rounded-lg border border-[#E5E6EB] p-6 animate-fadeInUp"
        style={{ animationDelay: '600ms' }}
        aria-label="最近动态"
      >
        <h2 className="text-[16px] font-semibold mb-6" style={{ color: '#1D2129' }}>
          最近动态
        </h2>

        <div className="relative">
          {/* vertical timeline line */}
          <div
            className="absolute left-[3px] top-1 bottom-1 w-px rounded-full"
            style={{ background: '#E5E6EB' }}
          />

          <div className="space-y-6">
            {activities.map((item, i) => (
              <div key={i} className="relative flex items-start gap-4 pl-5">
                {/* dot */}
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
                    {item.time}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </Layout>
  )
}
