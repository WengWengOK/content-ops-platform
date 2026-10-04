import type {
  CommentSchedulerStatus,
  CommentSourceStatus,
  CommentStats,
  CommentWatch,
  CredentialProbeResult,
  OpsOverview,
  PlatformComment,
  PlatformCredential,
} from '@/types'
import { INTENT_COLORS, INTENTS, SENTIMENT_COLORS, SENTIMENTS, STATUS_CN, type DialogTurn } from './constants'

/** CommentStatsCards：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface CommentStatsCardsProps {
  stats: CommentStats
  intent: string
  sentiment: string
}

export function CommentStatsCards({
  stats,
  intent,
  sentiment,
}: CommentStatsCardsProps) {
  return (
    <>
      {/* 统计区 */}
      <div className="mb-4 grid grid-cols-2 gap-4">
        <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
          <div className="mb-2 text-sm font-medium" style={{ color: '#1D2129' }}>意图分布</div>
          <div className="flex flex-wrap gap-2">
            {stats.intent.length === 0 && <span className="text-xs" style={{ color: '#86909C' }}>暂无数据，先采集评论</span>}
            {stats.intent.map((s) => (
              <span
                key={s.intent}
                className="rounded-full px-3 py-1 text-xs font-medium"
                style={{ background: '#F2F3F5', color: INTENT_COLORS[s.intent ?? ''] ?? '#4E5969' }}
              >
                {s.intent ?? '未识别'} · {s.cnt}
              </span>
            ))}
          </div>
        </div>
        <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
          <div className="mb-2 text-sm font-medium" style={{ color: '#1D2129' }}>情感分布</div>
          <div className="flex flex-wrap gap-2">
            {stats.sentiment.length === 0 && <span className="text-xs" style={{ color: '#86909C' }}>暂无数据，先采集评论</span>}
            {stats.sentiment.map((s) => (
              <span
                key={s.sentiment}
                className="rounded-full px-3 py-1 text-xs font-medium"
                style={{ background: '#F2F3F5', color: SENTIMENT_COLORS[s.sentiment ?? ''] ?? '#4E5969' }}
              >
                {s.sentiment ?? 'UNKNOWN'} · {s.cnt}
              </span>
            ))}
          </div>
        </div>
      </div>
    </>
  )
}