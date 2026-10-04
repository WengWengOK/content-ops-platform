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

/** CommentsToolbar：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface CommentsToolbarProps {
  platform: string
  setPlatform: (v: string) => void
  workId: string
  setWorkId: (v: string) => void
  intent: string
  setIntent: (v: string) => void
  sentiment: string
  setSentiment: (v: string) => void
  relation: string
  setRelation: (v: string) => void
  error: string
  handleCollect: () => void
  handleAnalyzeAll: () => void
  loadComments: () => void
  loadStats: () => void
}

export function CommentsToolbar({
  platform,
  setPlatform,
  workId,
  setWorkId,
  intent,
  setIntent,
  sentiment,
  setSentiment,
  relation,
  setRelation,
  error,
  handleCollect,
  handleAnalyzeAll,
  loadComments,
  loadStats,
}: CommentsToolbarProps) {
  return (
    <>
      {/* 控制区 */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>作品 ID（workId）</span>
            <input
              value={workId}
              onChange={(e) => setWorkId(e.target.value)}
              placeholder="如 9f2c…，留空查看全部"
              className="w-56 rounded-lg border px-3 py-2 text-sm outline-none focus:border-[#FF2D5E]"
              style={{ borderColor: '#E5E6EB' }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>平台</span>
            <select
              value={platform}
              onChange={(e) => setPlatform(e.target.value)}
              className="w-36 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              <option value="xiaohongshu">小红书</option>
              <option value="douyin">抖音</option>
              <option value="wechat">微信</option>
              <option value="kuaishou">快手</option>
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>关系</span>
            <select
              value={relation}
              onChange={(e) => setRelation(e.target.value)}
              className="w-28 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              <option value="FAN">粉丝</option>
              <option value="FOLLOWING">关注</option>
              <option value="FRIEND">好友</option>
              <option value="REGULAR">常客</option>
              <option value="SELF">自己</option>
              <option value="STRANGER">路人</option>
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>意图</span>
            <select
              value={intent}
              onChange={(e) => setIntent(e.target.value)}
              className="w-32 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              {INTENTS.map((i) => (
                <option key={i} value={i}>{i}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>情感</span>
            <select
              value={sentiment}
              onChange={(e) => setSentiment(e.target.value)}
              className="w-32 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              {SENTIMENTS.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </div>
          <button
            onClick={handleCollect}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white"
            style={{ background: '#FF2D5E' }}
          >
            📥 采集评论
          </button>
          <button
            onClick={handleAnalyzeAll}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white"
            style={{ background: '#165DFF' }}
          >
            ✨ AI 批量分析
          </button>
          <button
            onClick={() => { void loadComments(); void loadStats() }}
            className="rounded-lg border px-4 py-2 text-sm font-medium"
            style={{ borderColor: '#E5E6EB', color: '#4E5969' }}
          >
            刷新
          </button>
        </div>
        {error && (
          <div className="mt-3 rounded-lg px-3 py-2 text-sm" style={{ background: '#FFF0F0', color: '#F53F3F' }}>
            {error}
          </div>
        )}
      </div>
    </>
  )
}