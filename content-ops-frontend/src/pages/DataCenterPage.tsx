import { useState, useRef, useEffect } from 'react'
import { Layout } from '@/components/layout/Layout'

/* ================================================================ */
/*  Types                                                           */
/* ================================================================ */

type TrendDirection = 'up' | 'down' | null

interface Metric {
  label: string
  value: string
  change: string
  trend: TrendDirection
  positive: boolean
  note?: string
}

type TabId = 'traffic' | 'interaction' | 'follower' | 'content'
type CompareMetric = 'views' | 'interactions' | 'followers'
type RangeId = '7' | '30' | '90'
type SubPeriodId = '7' | '30'

interface SummaryStat {
  label: string
  value: string
  change: string
  icon: 'users' | 'eye' | 'hand' | 'file'
  iconColor: string
  iconBg: string
}

interface PlatformInfo {
  key: string
  name: string
  color: string
  iconBg: string
  account: string
  icon: 'flame' | 'chat' | 'play' | 'tv'
  goLinkColor: string
  goLinkBg: string
  goLinkHref: string
  coreMetrics: { label: string; value: string; isGreen?: boolean }[]
  detailMetrics: { label: string; value: string }[]
  revenue: string
  revenueColor: string
  miniTrend: number[]
}

/* ================================================================ */
/*  Pre-computed 30-day chart data (stable across renders)          */
/* ================================================================ */

const dayLabels30 = Array.from({ length: 30 }, (_, i) => {
  const d = new Date(2026, 6, 26 - 29 + i)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const traffic30Values = Array.from(
  { length: 30 },
  () => Math.floor(5000 + Math.random() * 6000)
)

const follower30Values = Array.from(
  { length: 30 },
  (_, i) => 11000 + Math.floor(i * 60 + Math.random() * 100)
)

/* ================================================================ */
/*  Mock Data                                                       */
/* ================================================================ */

const summaryStats: SummaryStat[] = [
  {
    label: '总粉丝数',
    value: '12,580',
    change: '+280 新增',
    icon: 'users',
    iconColor: '#FF2D5E',
    iconBg: '#FFF0F5',
  },
  {
    label: '总曝光量',
    value: '54,760',
    change: '+12.3%',
    icon: 'eye',
    iconColor: '#165DFF',
    iconBg: '#E8F3FF',
  },
  {
    label: '总互动量',
    value: '6,847',
    change: '+9.8%',
    icon: 'hand',
    iconColor: '#FE2C55',
    iconBg: '#FFF0F3',
  },
  {
    label: '总发布数',
    value: '32',
    change: '+3 新增',
    icon: 'file',
    iconColor: '#00A1D6',
    iconBg: '#E8F8FF',
  },
]

const platforms: PlatformInfo[] = [
  {
    key: 'xhs',
    name: '小红书',
    color: '#FF2D5E',
    iconBg: '#FFF0F5',
    account: '@ 成长日记',
    icon: 'flame',
    goLinkColor: '#FF2D5E',
    goLinkBg: '#FFF0F5',
    goLinkHref: 'https://creator.xiaohongshu.com',
    coreMetrics: [
      { label: '粉丝总数', value: '8,520' },
      { label: '新增粉丝', value: '+156', isGreen: true },
      { label: '曝光量', value: '28,450' },
      { label: '互动量', value: '2,777' },
    ],
    detailMetrics: [
      { label: '点赞', value: '2,187' },
      { label: '评论', value: '412' },
      { label: '收藏', value: '890' },
      { label: '转发', value: '178' },
      { label: '观看数', value: '28,450' },
      { label: '平均阅读时长', value: '2分18秒' },
    ],
    revenue: '¥1,268.50',
    revenueColor: '#FF2D5E',
    miniTrend: [18, 22, 15, 28, 20, 32, 21],
  },
  {
    key: 'gh',
    name: '公众号',
    color: '#165DFF',
    iconBg: '#E8F3FF',
    account: '@ 干货分享站',
    icon: 'chat',
    goLinkColor: '#165DFF',
    goLinkBg: '#E8F3FF',
    goLinkHref: 'https://mp.weixin.qq.com',
    coreMetrics: [
      { label: '粉丝总数', value: '2,890' },
      { label: '新增粉丝', value: '+89', isGreen: true },
      { label: '曝光量', value: '18,920' },
      { label: '互动量', value: '1,658' },
    ],
    detailMetrics: [
      { label: '点赞', value: '1,345' },
      { label: '评论', value: '215' },
      { label: '收藏', value: '420' },
      { label: '转发', value: '98' },
      { label: '阅读量', value: '18,920' },
      { label: '平均阅读时长', value: '3分42秒' },
    ],
    revenue: '¥856.20',
    revenueColor: '#165DFF',
    miniTrend: [10, 14, 8, 18, 12, 16, 11],
  },
  {
    key: 'dy',
    name: '抖音',
    color: '#FE2C55',
    iconBg: '#FFF0F3',
    account: '@ 短视频日记',
    icon: 'play',
    goLinkColor: '#FE2C55',
    goLinkBg: '#FFF0F3',
    goLinkHref: 'https://creator.douyin.com',
    coreMetrics: [
      { label: '粉丝总数', value: '3,200' },
      { label: '新增粉丝', value: '+23', isGreen: true },
      { label: '曝光量', value: '12,800' },
      { label: '互动量', value: '1,560' },
    ],
    detailMetrics: [
      { label: '点赞', value: '980' },
      { label: '评论', value: '245' },
      { label: '收藏', value: '380' },
      { label: '转发', value: '89' },
      { label: '阅读量', value: '12,800' },
      { label: '平均阅读时长', value: '45秒' },
    ],
    revenue: '¥328.80',
    revenueColor: '#FE2C55',
    miniTrend: [8, 12, 10, 15, 11, 14, 9],
  },
  {
    key: 'bl',
    name: '哔哩哔哩',
    color: '#00A1D6',
    iconBg: '#E8F8FF',
    account: '@ 二次元笔记',
    icon: 'tv',
    goLinkColor: '#00A1D6',
    goLinkBg: '#E8F8FF',
    goLinkHref: 'https://member.bilibili.com',
    coreMetrics: [
      { label: '粉丝总数', value: '1,560' },
      { label: '新增粉丝', value: '+12', isGreen: true },
      { label: '曝光量', value: '8,450' },
      { label: '互动量', value: '620' },
    ],
    detailMetrics: [
      { label: '点赞', value: '485' },
      { label: '评论', value: '92' },
      { label: '收藏', value: '128' },
      { label: '转发', value: '36' },
      { label: '阅读量', value: '8,450' },
      { label: '平均阅读时长', value: '3分28秒' },
    ],
    revenue: '¥85.00',
    revenueColor: '#00A1D6',
    miniTrend: [5, 8, 6, 10, 7, 9, 6],
  },
]

const analysisTabs: { id: TabId; label: string; icon: 'eye' | 'hand' | 'users' | 'file' }[] = [
  { id: 'traffic', label: '流量数据', icon: 'eye' },
  { id: 'interaction', label: '互动数据', icon: 'hand' },
  { id: 'follower', label: '粉丝数据', icon: 'users' },
  { id: 'content', label: '作品数据', icon: 'file' },
]

const tabMetrics: Record<TabId, Metric[]> = {
  traffic: [
    { label: '总曝光量', value: '54,760', change: '+12.3%', trend: 'up', positive: true },
    { label: '总观看数', value: '42,830', change: '+8.7%', trend: 'up', positive: true },
    { label: '封面点击率', value: '23.6%', change: '+2.1%', trend: 'up', positive: true },
    { label: '平均阅读时长', value: '3分24秒', change: '+5.4%', trend: 'up', positive: true },
    { label: '阅读总时长', value: '48.6小时', change: '+11.2%', trend: 'up', positive: true },
    { label: '内容完读率', value: '67.8%', change: '-1.3%', trend: 'down', positive: false },
  ],
  interaction: [
    { label: '总点赞数', value: '4,107', change: '+8.7%', trend: 'up', positive: true },
    { label: '总评论数', value: '737', change: '+15.2%', trend: 'up', positive: true },
    { label: '总收藏数', value: '1,285', change: '+22.4%', trend: 'up', positive: true },
    { label: '总转发数', value: '318', change: '-2.1%', trend: 'down', positive: false },
    { label: '互动率', value: '8.5%', change: '+0.3%', trend: 'up', positive: true },
    { label: '互动总量', value: '6,847', change: '+9.8%', trend: 'up', positive: true },
  ],
  follower: [
    { label: '总粉丝数', value: '12,580', change: '+156', trend: 'up', positive: true },
    { label: '新增粉丝', value: '280', change: '+23%', trend: 'up', positive: true },
    { label: '活跃粉丝', value: '8,920', change: '+5.1%', trend: 'up', positive: true },
    { label: '粉丝留存率', value: '71.2%', change: '+1.8%', trend: 'up', positive: true },
    { label: '净增粉丝', value: '267', change: '+18.9%', trend: 'up', positive: true },
    { label: '取关人数', value: '13', change: '-45.2%', trend: 'up', positive: true, note: '(越少越好)' },
  ],
  content: [
    { label: '总发布数', value: '32', change: '+3', trend: 'up', positive: true },
    { label: '已发布', value: '28', change: '--', trend: null, positive: true },
    { label: '草稿', value: '4', change: '--', trend: null, positive: true },
    { label: '平均互动', value: '214', change: '-8.2%', trend: 'down', positive: false },
    { label: '高互动作品', value: '8', change: '+2', trend: 'up', positive: true },
    { label: '发布频率', value: '4.6篇/周', change: '+0.3', trend: 'up', positive: true },
  ],
}

const trafficChartData: Record<SubPeriodId, { labels: string[]; values: number[] }> = {
  '7': {
    labels: ['07-19', '07-20', '07-21', '07-22', '07-23', '07-24', '07-25'],
    values: [6520, 7340, 6980, 8120, 7850, 9230, 8720],
  },
  '30': {
    labels: dayLabels30,
    values: traffic30Values,
  },
}

const followerChartData: Record<SubPeriodId, { labels: string[]; values: number[] }> = {
  '7': {
    labels: ['07-19', '07-20', '07-21', '07-22', '07-23', '07-24', '07-25'],
    values: [12324, 12380, 12410, 12452, 12489, 12520, 12580],
  },
  '30': {
    labels: dayLabels30,
    values: follower30Values,
  },
}

const interactionBarData: Record<string, { likes: number; comments: number; saves: number; shares: number }> = {
  xhs: { likes: 2187, comments: 412, saves: 890, shares: 178 },
  gh: { likes: 1345, comments: 215, saves: 420, shares: 98 },
  dy: { likes: 980, comments: 245, saves: 380, shares: 89 },
  bl: { likes: 485, comments: 92, saves: 128, shares: 36 },
}

const contentBarData = {
  labels: ['小红书', '公众号', '抖音', '哔哩哔哩'],
  values: [14, 10, 8, 6],
  colors: ['#FF2D5E', '#165DFF', '#FE2C55', '#00A1D6'],
}

const compareData: Record<CompareMetric, { xhs: number; gh: number; dy: number; bl: number }> = {
  views: { xhs: 28450, gh: 18920, dy: 12800, bl: 8450 },
  interactions: { xhs: 2777, gh: 1658, dy: 1560, bl: 620 },
  followers: { xhs: 156, gh: 89, dy: 45, bl: 28 },
}

// Pre-computed previous-period values (deterministic ~65-80% of current)
const comparePrevData: Record<CompareMetric, { xhs: number; gh: number; dy: number; bl: number }> = {
  views: { xhs: 21337, gh: 13244, dy: 8960, bl: 5577 },
  interactions: { xhs: 2082, gh: 1243, dy: 1170, bl: 434 },
  followers: { xhs: 117, gh: 66, dy: 33, bl: 19 },
}

const comparePlatforms = [
  { key: 'xhs' as const, name: '小红书', color: '#FF2D5E' },
  { key: 'gh' as const, name: '公众号', color: '#165DFF' },
  { key: 'dy' as const, name: '抖音', color: '#FE2C55' },
  { key: 'bl' as const, name: '哔哩哔哩', color: '#00A1D6' },
]

const dateRanges: { id: RangeId; label: string }[] = [
  { id: '7', label: '近7天' },
  { id: '30', label: '近30天' },
  { id: '90', label: '近90天' },
]

const subPeriods: { id: SubPeriodId; label: string }[] = [
  { id: '7', label: '近7日' },
  { id: '30', label: '近30日' },
]

const compareMetrics: { id: CompareMetric; label: string }[] = [
  { id: 'views', label: '浏览量' },
  { id: 'interactions', label: '互动量' },
  { id: 'followers', label: '粉丝增长' },
]

/* ================================================================ */
/*  Helpers                                                         */
/* ================================================================ */

const formatNumber = (n: number): string =>
  n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')

/* ================================================================ */
/*  Icon Components                                                 */
/* ================================================================ */

function SummaryIcon({ type, color }: { type: SummaryStat['icon']; color: string }) {
  const paths: Record<string, React.ReactNode> = {
    users: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    ),
    eye: (
      <>
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z"
        />
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"
        />
      </>
    ),
    hand: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M6.633 10.25c.806 0 1.533-.446 2.031-1.08a9.041 9.041 0 0 1 2.861-2.4c.723-.384 1.35-.956 1.653-1.715a4.498 4.498 0 0 0 .322-1.672V2.75a.75.75 0 0 1 .75-.75 2.25 2.25 0 0 1 2.25 2.25c0 1.152-.26 2.243-.723 3.218-.266.558.107 1.282.725 1.282m0 0h3.126c1.026 0 1.945.694 2.054 1.715.045.422.068.85.068 1.285a11.95 11.95 0 0 1-2.649 7.521c-.388.482-.987.729-1.605.729H14.23c-.483 0-.964-.078-1.423-.23l-3.114-1.04a4.501 4.501 0 0 0-1.423-.23H5.904m7.598-4.5H7.092"
      />
    ),
    file: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m5.231 13.481L15 17.25m-4.5-15H5.625c-.621 0-1.125.504-1.125 1.125v16.5c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Zm3.75 11.625a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    ),
  }
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8} style={{ color }}>
      {paths[type]}
    </svg>
  )
}

function PlatformIcon({ type, color }: { type: PlatformInfo['icon']; color: string }) {
  const paths: Record<string, React.ReactNode> = {
    flame: (
      <>
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15.362 5.214A8.252 8.252 0 0 1 12 21 8.25 8.25 0 0 1 6.038 7.047 8.287 8.287 0 0 0 9 9.601a8.983 8.983 0 0 1 3.361-6.867 8.21 8.21 0 0 0 3 2.48Z"
        />
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M12 18a3.75 3.75 0 0 0 .495-7.468 5.99 5.99 0 0 0-1.925 3.547 5.975 5.975 0 0 1-2.133-1.001A3.75 3.75 0 0 0 12 18Z"
        />
      </>
    ),
    chat: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 0 1 .778-.332 48.294 48.294 0 0 0 5.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"
      />
    ),
    play: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347a1.125 1.125 0 0 1-1.667-.986V5.653Z"
      />
    ),
    tv: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M9.879 7.519c1.171-1.025 3.071-1.025 4.242 0 1.172 1.025 1.172 2.687 0 3.712-.203.179-.43.326-.67.442-.745.361-1.45.999-1.45 1.827m0 0v.75m0-3.375c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125M12 18.75c-.34 0-.676-.003-1.009-.01"
      />
    ),
  }
  return (
    <svg style={{ color, width: 18, height: 18 }} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      {paths[type]}
    </svg>
  )
}

function AnalysisTabIcon({ type }: { type: 'eye' | 'hand' | 'users' | 'file' }) {
  const paths: Record<string, React.ReactNode> = {
    eye: (
      <>
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z"
        />
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"
        />
      </>
    ),
    hand: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M6.633 10.25c.806 0 1.533-.446 2.031-1.08a9.041 9.041 0 0 1 2.861-2.4c.723-.384 1.35-.956 1.653-1.715a4.498 4.498 0 0 0 .322-1.672V2.75a.75.75 0 0 1 .75-.75 2.25 2.25 0 0 1 2.25 2.25c0 1.152-.26 2.243-.723 3.218-.266.558.107 1.282.725 1.282m0 0h3.126c1.026 0 1.945.694 2.054 1.715.045.422.068.85.068 1.285a11.95 11.95 0 0 1-2.649 7.521c-.388.482-.987.729-1.605.729H14.23c-.483 0-.964-.078-1.423-.23l-3.114-1.04a4.501 4.501 0 0 0-1.423-.23H5.904m7.598-4.5H7.092"
      />
    ),
    users: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    ),
    file: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m5.231 13.481L15 17.25m-4.5-15H5.625c-.621 0-1.125.504-1.125 1.125v16.5c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Zm3.75 11.625a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    ),
  }
  return (
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      {paths[type]}
    </svg>
  )
}

function UpArrow() {
  return (
    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 19.5l15-15m0 0H8.25m11.25 0v11.25" />
    </svg>
  )
}

function DownArrow() {
  return (
    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 4.5l15 15m0 0V8.25m0 11.25H8.25" />
    </svg>
  )
}

function ExternalLinkArrow() {
  return (
    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 19.5l15-15m0 0H8.25m11.25 0v11.25" />
    </svg>
  )
}

/* ================================================================ */
/*  Chart Components (SVG)                                          */
/* ================================================================ */

/** Full-size line chart for traffic & follower tabs. */
function LineChartSVG({ labels, values, color }: { labels: string[]; values: number[]; color: string }) {
  const W = 1120
  const H = 200
  const padL = 55
  const padR = 20
  const padT = 15
  const padB = 30
  const chartW = W - padL - padR
  const chartH = H - padT - padB

  const maxVal = Math.max(...values) * 1.1
  const minVal = Math.min(...values) * 0.9
  const range = maxVal - minVal || 1

  const n = values.length
  const step = chartW / Math.max(n - 1, 1)
  const points = values.map((v, i) => ({
    x: padL + i * step,
    y: padT + chartH - ((v - minVal) / range) * chartH,
  }))

  const yTicks = 4
  const yTickValues = Array.from({ length: yTicks + 1 }, (_, i) => minVal + range * (i / yTicks))
  const labelStep = n <= 10 ? 1 : Math.ceil(n / 8)

  const areaPath =
    `M ${points[0].x},${padT + chartH} ` +
    points.map((p) => `L ${p.x},${p.y}`).join(' ') +
    ` L ${points[n - 1].x},${padT + chartH} Z`

  const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x},${p.y}`).join(' ')

  const gradId = `lc-grad-${color.replace('#', '')}`

  return (
    <svg width="100%" height="200" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" fill="none">
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.2" />
          <stop offset="100%" stopColor={color} stopOpacity="0.02" />
        </linearGradient>
      </defs>

      {/* Y-axis grid lines + labels */}
      {yTickValues.map((yVal, i) => {
        const y = padT + chartH - (i / yTicks) * chartH
        return (
          <g key={`y-${i}`}>
            <line x1={padL} y1={y} x2={W - padR} y2={y} stroke="#F2F3F5" strokeWidth={1} />
            <text x={padL - 10} y={y + 4} textAnchor="end" fill="#86909C" fontSize={11} fontFamily="Inter, sans-serif">
              {formatNumber(Math.round(yVal))}
            </text>
          </g>
        )
      })}

      {/* X-axis labels */}
      {points.map((p, i) => {
        if (i % labelStep === 0 || i === n - 1) {
          return (
            <text
              key={`x-${i}`}
              x={p.x}
              y={H - 6}
              textAnchor="middle"
              fill="#86909C"
              fontSize={10}
              fontFamily="Inter, sans-serif"
            >
              {labels[i]}
            </text>
          )
        }
        return null
      })}

      {/* Area fill */}
      <path d={areaPath} fill={`url(#${gradId})`} />

      {/* Line */}
      <path
        d={linePath}
        fill="none"
        stroke={color}
        strokeWidth={2.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />

      {/* Dots */}
      {points.map((p, i) => (
        <circle
          key={`dot-${i}`}
          cx={p.x}
          cy={p.y}
          r={3.5}
          fill={color}
          stroke="white"
          strokeWidth={2}
          style={{ cursor: 'pointer' }}
        />
      ))}
    </svg>
  )
}

/** Mini trend sparkline for platform cards. */
function MiniTrendSVG({ data, color }: { data: number[]; color: string }) {
  const W = 100
  const H = 32
  const padT = 4
  const padB = 4
  const padL = 2
  const padR = 2
  const chartW = W - padL - padR
  const chartH = H - padT - padB

  const maxVal = Math.max(...data) * 1.15
  const minVal = 0
  const range = maxVal - minVal || 1

  const n = data.length
  const step = chartW / Math.max(n - 1, 1)
  const points = data.map((v, i) => ({
    x: padL + i * step,
    y: padT + chartH - ((v - minVal) / range) * chartH,
  }))

  const areaPath =
    `M ${points[0].x},${padT + chartH} ` +
    points.map((p) => `L ${p.x},${p.y}`).join(' ') +
    ` L ${points[n - 1].x},${padT + chartH} Z`

  const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x},${p.y}`).join(' ')

  const gradId = `mt-grad-${color.replace('#', '')}`

  return (
    <svg width="100" height="32" viewBox={`0 0 ${W} ${H}`} fill="none">
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.25" />
          <stop offset="100%" stopColor={color} stopOpacity="0.02" />
        </linearGradient>
      </defs>
      <path d={areaPath} fill={`url(#${gradId})`} />
      <path
        d={linePath}
        fill="none"
        stroke={color}
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle
        cx={points[n - 1].x}
        cy={points[n - 1].y}
        r={2}
        fill={color}
        stroke="white"
        strokeWidth={1.5}
      />
    </svg>
  )
}

/** Grouped bar chart for interaction tab (platforms × categories). */
function GroupedBarChartSVG() {
  const categories = ['likes', 'comments', 'saves', 'shares'] as const
  const catLabels = ['点赞', '评论', '收藏', '转发']

  const W = 800
  const H = 200
  const padL = 50
  const padR = 20
  const padT = 10
  const padB = 40
  const chartW = W - padL - padR
  const chartH = H - padT - padB

  let maxVal = 0
  comparePlatforms.forEach((p) => {
    categories.forEach((c) => {
      maxVal = Math.max(maxVal, interactionBarData[p.key][c])
    })
  })
  maxVal = maxVal * 1.15

  const groupWidth = chartW / comparePlatforms.length
  const barWidth = (groupWidth * 0.6) / categories.length
  const groupGap = groupWidth * 0.4

  return (
    <svg width="100%" height="200" viewBox={`0 0 ${W} ${H}`} fill="none">
      {/* Y-axis */}
      {Array.from({ length: 5 }, (_, i) => {
        const y = padT + chartH - (i / 4) * chartH
        const val = Math.round(maxVal * (i / 4))
        return (
          <g key={`y-${i}`}>
            <line x1={padL} y1={y} x2={W - padR} y2={y} stroke="#F2F3F5" strokeWidth={1} />
            <text x={padL - 8} y={y + 4} textAnchor="end" fill="#86909C" fontSize={10} fontFamily="Inter, sans-serif">
              {formatNumber(val)}
            </text>
          </g>
        )
      })}

      {/* Bars */}
      {comparePlatforms.map((p, pi) => {
        const groupX = padL + pi * groupWidth + groupGap / 2
        return (
          <g key={p.key}>
            {categories.map((c, ci) => {
              const val = interactionBarData[p.key][c]
              const barH = (val / maxVal) * chartH
              const x = groupX + ci * barWidth
              const y = padT + chartH - barH
              return (
                <rect
                  key={c}
                  x={x}
                  y={y}
                  width={barWidth - 2}
                  height={barH}
                  rx={3}
                  fill={p.color}
                  opacity={0.85}
                />
              )
            })}
            {/* X label */}
            <text
              x={padL + pi * groupWidth + groupWidth / 2}
              y={H - 12}
              textAnchor="middle"
              fill="#4E5969"
              fontSize={11}
              fontFamily="Inter, sans-serif"
            >
              {p.name}
            </text>
          </g>
        )
      })}

      {/* Legend */}
      {catLabels.map((label, i) => {
        const lx = W - padR - (catLabels.length - i) * 60
        return (
          <g key={label}>
            <rect x={lx} y={0} width={8} height={8} rx={2} fill="#C9CDD4" />
            <text x={lx + 12} y={8} fill="#86909C" fontSize={10} fontFamily="Inter, sans-serif">
              {label}
            </text>
          </g>
        )
      })}
    </svg>
  )
}

/** Horizontal bar chart for content tab. */
function HorizontalBarChartSVG() {
  const W = 800
  const H = 200
  const padL = 80
  const padR = 40
  const padT = 10
  const padB = 40
  const chartW = W - padL - padR
  const chartH = H - padT - padB
  const maxVal = Math.max(...contentBarData.values) * 1.15

  const barH = Math.min((chartH / contentBarData.labels.length) * 0.6, 28)
  const barGap = chartH / contentBarData.labels.length

  return (
    <svg width="100%" height="200" viewBox={`0 0 ${W} ${H}`} fill="none">
      {/* Y grid (vertical lines) */}
      {Array.from({ length: 5 }, (_, i) => {
        const x = padL + (i / 4) * chartW
        return (
          <g key={`grid-${i}`}>
            <line x1={x} y1={padT} x2={x} y2={padT + chartH} stroke="#F2F3F5" strokeWidth={1} />
            <text x={x} y={padT + chartH + 20} textAnchor="middle" fill="#86909C" fontSize={10} fontFamily="Inter, sans-serif">
              {Math.round(maxVal * (i / 4))}
            </text>
          </g>
        )
      })}

      {/* Bars */}
      {contentBarData.labels.map((label, i) => {
        const val = contentBarData.values[i]
        const barW = (val / maxVal) * chartW
        const y = padT + i * barGap + (barGap - barH) / 2
        return (
          <g key={label}>
            <text
              x={padL - 10}
              y={y + barH / 2 + 4}
              textAnchor="end"
              fill="#4E5969"
              fontSize={11}
              fontFamily="Inter, sans-serif"
            >
              {label}
            </text>
            <rect x={padL} y={y} width={barW} height={barH} rx={4} fill={contentBarData.colors[i]} opacity={0.85} />
            <text
              x={padL + barW + 8}
              y={y + barH / 2 + 4}
              fill="#1D2129"
              fontSize={11}
              fontWeight={600}
              fontFamily="Inter, sans-serif"
            >
              {val}
            </text>
          </g>
        )
      })}
    </svg>
  )
}

/** Paired grouped bar chart for platform comparison. */
function CompareBarChartSVG({ metric }: { metric: CompareMetric }) {
  const data = compareData[metric]
  const prevData = comparePrevData[metric]
  const values = comparePlatforms.map((p) => data[p.key])
  const maxVal = Math.max(...values) * 1.2

  const W = 800
  const H = 240
  const padL = 60
  const padR = 30
  const padT = 10
  const padB = 50
  const chartW = W - padL - padR
  const chartH = H - padT - padB

  const barWidth = (chartW / comparePlatforms.length) * 0.35
  const baseY = padT + chartH

  return (
    <svg width="100%" height="100%" viewBox={`0 0 ${W} ${H}`} fill="none">
      {/* Y grid */}
      {Array.from({ length: 6 }, (_, i) => {
        const y = padT + chartH - (i / 5) * chartH
        const val = Math.round(maxVal * (i / 5))
        return (
          <g key={`y-${i}`}>
            <line x1={padL} y1={y} x2={W - padR} y2={y} stroke="#F2F3F5" strokeWidth={1} />
            <text x={padL - 10} y={y + 4} textAnchor="end" fill="#86909C" fontSize={10} fontFamily="Inter, sans-serif">
              {formatNumber(val)}
            </text>
          </g>
        )
      })}

      {/* Baseline */}
      <line x1={padL} y1={baseY} x2={W - padR} y2={baseY} stroke="#E5E6EB" strokeWidth={1} />

      {/* Paired bars */}
      {comparePlatforms.map((p, pi) => {
        const val = data[p.key]
        const prevVal = prevData[p.key]
        const barH = (val / maxVal) * chartH
        const prevBarH = (prevVal / maxVal) * chartH
        const groupX = padL + pi * (chartW / comparePlatforms.length) + (chartW / comparePlatforms.length) / 2

        return (
          <g key={p.key}>
            {/* Previous period bar (lighter) */}
            <rect
              x={groupX - barWidth - 2}
              y={baseY - prevBarH}
              width={barWidth}
              height={prevBarH}
              rx={4}
              fill={p.color}
              opacity={0.25}
            />
            {/* Current period bar */}
            <rect
              x={groupX + 2}
              y={baseY - barH}
              width={barWidth}
              height={barH}
              rx={4}
              fill={p.color}
              opacity={0.85}
            />
            {/* Value label */}
            <text
              x={groupX + 2 + barWidth / 2}
              y={baseY - barH - 6}
              textAnchor="middle"
              fill="#1D2129"
              fontSize={11}
              fontWeight={600}
              fontFamily="Inter, sans-serif"
            >
              {formatNumber(val)}
            </text>
            {/* Platform name */}
            <text
              x={groupX}
              y={baseY + 20}
              textAnchor="middle"
              fill="#4E5969"
              fontSize={11}
              fontFamily="Inter, sans-serif"
            >
              {p.name}
            </text>
          </g>
        )
      })}

      {/* Legend for paired bars */}
      <rect x={W - padR - 160} y={padT} width={10} height={10} rx={3} fill="#C9CDD4" />
      <text x={W - padR - 146} y={padT + 9} fill="#86909C" fontSize={10} fontFamily="Inter, sans-serif">
        上一周期
      </text>
      <rect x={W - padR - 70} y={padT} width={10} height={10} rx={3} fill="#4E5969" />
      <text x={W - padR - 56} y={padT + 9} fill="#86909C" fontSize={10} fontFamily="Inter, sans-serif">
        本周期
      </text>
    </svg>
  )
}

/* ================================================================ */
/*  Metric Card (for analysis tab panels)                           */
/* ================================================================ */

function MetricCard({ metric }: { metric: Metric }) {
  const arrowColor = metric.positive ? '#00B42A' : '#F53F3F'
  return (
    <div className="rounded-xl border border-[#E5E6EB] p-4">
      <div className="text-xs font-medium mb-2" style={{ color: '#86909C' }}>
        {metric.label}
      </div>
      <div className="tabular-nums text-xl font-bold mb-1" style={{ color: '#1D2129' }}>
        {metric.value}
      </div>
      <div className="flex items-center gap-1">
        {metric.trend === 'up' && (
          <>
            <span style={{ color: arrowColor, display: 'inline-flex' }}>
              <UpArrow />
            </span>
            <span className="text-xs font-medium" style={{ color: arrowColor }}>
              {metric.change}
            </span>
          </>
        )}
        {metric.trend === 'down' && (
          <>
            <span style={{ color: arrowColor, display: 'inline-flex' }}>
              <DownArrow />
            </span>
            <span className="text-xs font-medium" style={{ color: arrowColor }}>
              {metric.change}
            </span>
          </>
        )}
        {metric.trend === null && (
          <span className="text-xs" style={{ color: '#86909C' }}>
            --
          </span>
        )}
        {metric.note && (
          <span className="text-[10px]" style={{ color: '#86909C' }}>
            {metric.note}
          </span>
        )}
      </div>
    </div>
  )
}

/* ================================================================ */
/*  Main Component                                                   */
/* ================================================================ */

export function DataCenterPage() {
  const [globalRange, setGlobalRange] = useState<RangeId>('7')
  const [activeTab, setActiveTab] = useState<TabId>('traffic')
  const [subPeriod, setSubPeriod] = useState<SubPeriodId>('7')
  const [compareMetric, setCompareMetric] = useState<CompareMetric>('views')
  const [toastMsg, setToastMsg] = useState<string | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const showToast = (msg: string) => {
    setToastMsg(msg)
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    toastTimerRef.current = setTimeout(() => setToastMsg(null), 2500)
  }

  useEffect(() => {
    return () => {
      if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    }
  }, [])

  const metrics = tabMetrics[activeTab]

  return (
    <Layout
      activeNav="data"
      pageTitle="数据中心"
      headerRight={
        <div className="flex items-center gap-1 rounded-lg p-1" style={{ background: '#F7F8FA' }}>
          {dateRanges.map((r) => (
            <button
              key={r.id}
              onClick={() => {
                setGlobalRange(r.id)
                showToast(`已切换至近${r.id}天数据`)
              }}
              className="rounded-lg text-[13px] font-medium transition-all"
              style={{
                padding: '6px 16px',
                border: '1px solid transparent',
                cursor: 'pointer',
                ...(globalRange === r.id
                  ? { background: '#165DFF', color: '#FFFFFF', borderColor: '#165DFF' }
                  : { color: '#4E5969', background: 'transparent' }),
              }}
            >
              {r.label}
            </button>
          ))}
        </div>
      }
    >
      {/* ================================================================ */}
      {/*  SECTION 1: Summary Cards (4-col grid)                            */}
      {/* ================================================================ */}
      <section
        className="grid grid-cols-4 gap-4 mb-6"
        style={{ animation: 'fadeInUp 400ms ease both' }}
        aria-label="账号概览"
      >
        {summaryStats.map((s) => (
          <div key={s.label} className="card-hover bg-white rounded-xl border border-[#E5E6EB] p-5">
            {/* Header row: label + icon */}
            <div className="flex items-center justify-between mb-3">
              <div className="text-xs font-medium" style={{ color: '#86909C' }}>
                {s.label}
              </div>
              <div
                className="w-8 h-8 rounded-lg flex items-center justify-center"
                style={{ background: s.iconBg }}
              >
                <SummaryIcon type={s.icon} color={s.iconColor} />
              </div>
            </div>
            {/* Value */}
            <div className="tabular-nums text-2xl font-bold mb-1" style={{ color: '#1D2129' }}>
              {s.value}
            </div>
            {/* Trend */}
            <div className="flex items-center gap-1">
              <span style={{ color: '#00B42A', display: 'inline-flex' }}>
                <UpArrow />
              </span>
              <span className="text-xs font-medium" style={{ color: '#00B42A' }}>
                {s.change}
              </span>
            </div>
          </div>
        ))}
      </section>

      {/* ================================================================ */}
      {/*  SECTION 2: Platform Detail Cards (2x2 grid)                      */}
      {/* ================================================================ */}
      <section
        className="grid grid-cols-2 gap-4 mb-6"
        style={{ animation: 'fadeInUp 450ms ease both' }}
        aria-label="各平台详细数据"
      >
        {platforms.map((p) => (
          <div
            key={p.key}
            className="platform-card card-hover bg-white rounded-xl border border-[#E5E6EB] p-5 pl-7"
            style={{ borderLeft: 'none', position: 'relative', overflow: 'hidden' }}
          >
            {/* Left color bar */}
            <div
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                bottom: 0,
                width: 4,
                background: p.color,
                borderRadius: '12px 0 0 12px',
              }}
            />

            {/* Card Header */}
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2.5">
                <div
                  className="w-8 h-8 rounded-lg flex items-center justify-center"
                  style={{ background: p.iconBg, color: p.color }}
                >
                  <PlatformIcon type={p.icon} color={p.color} />
                </div>
                <div>
                  <div className="text-sm font-semibold" style={{ color: '#1D2129' }}>
                    {p.name}
                  </div>
                  <div className="text-xs" style={{ color: '#86909C' }}>
                    {p.account}
                  </div>
                </div>
              </div>
              <a
                href={p.goLinkHref}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 rounded-lg text-xs font-medium transition-all"
                style={{
                  padding: '6px 14px',
                  color: p.goLinkColor,
                  background: p.goLinkBg,
                  border: '1px solid transparent',
                  textDecoration: 'none',
                }}
              >
                前往平台数据中心
                <ExternalLinkArrow />
              </a>
            </div>

            {/* Core Metrics Row */}
            <div
              className="grid grid-cols-4 gap-3 mb-4 pb-4"
              style={{ borderBottom: '1px solid #F2F3F5' }}
            >
              {p.coreMetrics.map((m) => (
                <div key={m.label}>
                  <div className="text-xs mb-1" style={{ color: '#86909C' }}>
                    {m.label}
                  </div>
                  <div
                    className="tabular-nums text-base font-bold"
                    style={{ color: m.isGreen ? '#00B42A' : '#1D2129' }}
                  >
                    {m.value}
                  </div>
                </div>
              ))}
            </div>

            {/* Detailed Metrics Row */}
            <div
              className="grid grid-cols-6 gap-2 mb-4 pb-4 text-xs"
              style={{ borderBottom: '1px solid #F2F3F5' }}
            >
              {p.detailMetrics.map((m) => (
                <div key={m.label}>
                  <div style={{ color: '#86909C' }}>{m.label}</div>
                  <div className="tabular-nums font-semibold" style={{ color: '#1D2129' }}>
                    {m.value}
                  </div>
                </div>
              ))}
            </div>

            {/* Revenue + Mini Trend Chart */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-xs" style={{ color: '#86909C' }}>
                  预估收益
                </span>
                <span className="tabular-nums text-sm font-bold" style={{ color: p.revenueColor }}>
                  {p.revenue}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[10px]" style={{ color: '#86909C' }}>
                  7日涨粉趋势
                </span>
                <MiniTrendSVG data={p.miniTrend} color={p.color} />
              </div>
            </div>
          </div>
        ))}
      </section>

      {/* ================================================================ */}
      {/*  SECTION 3: Analysis Trends (tab-based)                           */}
      {/* ================================================================ */}
      <section className="mb-6" style={{ animation: 'fadeInUp 500ms ease both' }} aria-label="数据分析">
        {/* Tab Header */}
        <div className="bg-white rounded-t-xl border border-b-0 border-[#E5E6EB] px-5 pt-4 pb-0">
          <div className="flex items-center justify-between">
            {/* Analysis tabs */}
            <div className="flex items-center gap-1 rounded-lg p-1" style={{ background: '#F7F8FA' }}>
              {analysisTabs.map((tab) => {
                const active = activeTab === tab.id
                return (
                  <button
                    key={tab.id}
                    onClick={() => setActiveTab(tab.id)}
                    className="flex items-center gap-1.5 rounded-lg text-[13px] font-medium transition-all"
                    style={{
                      padding: '8px 20px',
                      border: '1px solid transparent',
                      cursor: 'pointer',
                      ...(active
                        ? {
                            background: '#FFFFFF',
                            color: '#165DFF',
                            borderColor: '#E5E6EB',
                            boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
                          }
                        : {
                            color: '#4E5969',
                            background: 'transparent',
                          }),
                    }}
                  >
                    <AnalysisTabIcon type={tab.icon} />
                    {tab.label}
                  </button>
                )
              })}
            </div>
            {/* Sub-period tabs */}
            <div className="flex items-center gap-1">
              {subPeriods.map((sp) => {
                const active = subPeriod === sp.id
                return (
                  <button
                    key={sp.id}
                    onClick={() => setSubPeriod(sp.id)}
                    className="rounded-md text-xs font-medium transition-all"
                    style={{
                      padding: '4px 14px',
                      border: '1px solid transparent',
                      cursor: 'pointer',
                      ...(active
                        ? { background: '#E8F3FF', color: '#165DFF', borderColor: '#B8D4FF' }
                        : { color: '#86909C', background: 'transparent' }),
                    }}
                  >
                    {sp.label}
                  </button>
                )
              })}
            </div>
          </div>
        </div>

        {/* Tab Content Area */}
        <div className="bg-white rounded-b-xl border border-[#E5E6EB] p-5">
          {/* Metric cards grid */}
          <div className="grid grid-cols-3 gap-4 mb-5">
            {metrics.map((m) => (
              <MetricCard key={m.label} metric={m} />
            ))}
          </div>

          {/* Chart area - different chart per tab */}
          {activeTab === 'traffic' && (
            <div style={{ width: '100%', height: 200, position: 'relative' }}>
              <LineChartSVG
                labels={trafficChartData[subPeriod].labels}
                values={trafficChartData[subPeriod].values}
                color="#165DFF"
              />
            </div>
          )}
          {activeTab === 'interaction' && (
            <div className="flex items-center justify-center" style={{ height: 200 }}>
              <GroupedBarChartSVG />
            </div>
          )}
          {activeTab === 'follower' && (
            <div style={{ width: '100%', height: 200, position: 'relative' }}>
              <LineChartSVG
                labels={followerChartData[subPeriod].labels}
                values={followerChartData[subPeriod].values}
                color="#00A1D6"
              />
            </div>
          )}
          {activeTab === 'content' && (
            <div className="flex items-center justify-center" style={{ height: 200 }}>
              <HorizontalBarChartSVG />
            </div>
          )}
        </div>
      </section>

      {/* ================================================================ */}
      {/*  SECTION 4: Platform Comparison                                   */}
      {/* ================================================================ */}
      <section style={{ animation: 'fadeInUp 600ms ease both' }} aria-label="平台数据对比">
        <div className="bg-white rounded-xl border border-[#E5E6EB] p-5">
          {/* Header */}
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-sm font-semibold" style={{ color: '#1D2129' }}>
              平台数据对比
            </h2>
            <div className="flex items-center gap-3">
              {/* Compare metric tabs */}
              <div className="flex items-center gap-1">
                {compareMetrics.map((cm) => {
                  const active = compareMetric === cm.id
                  return (
                    <button
                      key={cm.id}
                      onClick={() => setCompareMetric(cm.id)}
                      className="rounded-md text-xs font-medium transition-all"
                      style={{
                        padding: '4px 14px',
                        border: '1px solid transparent',
                        cursor: 'pointer',
                        ...(active
                          ? { background: '#E8F3FF', color: '#165DFF', borderColor: '#B8D4FF' }
                          : { color: '#86909C', background: 'transparent' }),
                      }}
                    >
                      {cm.label}
                    </button>
                  )
                })}
              </div>
              {/* Compare button */}
              <button
                onClick={() => showToast('对比分析报告生成中，请稍候...')}
                className="flex items-center gap-2 rounded-lg text-xs font-medium text-white transition-colors"
                style={{ background: '#165DFF', padding: '8px 16px', border: 'none', cursor: 'pointer' }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = '#4080FF'
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = '#165DFF'
                }}
              >
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M7.5 21 3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5"
                  />
                </svg>
                对比数据分析
              </button>
            </div>
          </div>

          {/* Legend */}
          <div className="flex items-center gap-5 mb-4 text-xs" style={{ color: '#86909C' }}>
            {comparePlatforms.map((p) => (
              <span key={p.key} className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-full" style={{ background: p.color }} />
                {p.name}
              </span>
            ))}
          </div>

          {/* Grouped Bar Chart */}
          <div style={{ height: 240 }}>
            <CompareBarChartSVG metric={compareMetric} />
          </div>
        </div>
      </section>

      {/* ================================================================ */}
      {/*  Toast Notification                                               */}
      {/* ================================================================ */}
      {toastMsg && (
        <div
          style={{
            position: 'fixed',
            top: 80,
            right: 24,
            zIndex: 9999,
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
            animation: 'fadeInUp 0.3s ease both',
          }}
        >
          <svg className="w-4 h-4 flex-shrink-0" style={{ color: '#165DFF' }} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="m11.25 11.25.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z"
            />
          </svg>
          <span>{toastMsg}</span>
        </div>
      )}
    </Layout>
  )
}
