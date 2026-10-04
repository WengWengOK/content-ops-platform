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

/** WatchMonitorCard：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface WatchMonitorCardProps {
  platform: string
  watches: CommentWatch[]
  scheduler: CommentSchedulerStatus | null
  sourceStatus: CommentSourceStatus | null
  credentials: PlatformCredential[]
  selectedCredential: string
  setSelectedCredential: (v: string) => void
  watchInput: string
  setWatchInput: (v: string) => void
  watchXsecToken: string
  setWatchXsecToken: (v: string) => void
  watchAutoAnalyze: boolean
  setWatchAutoAnalyze: (v: boolean) => void
  watchBusy: string
  unread: Record<string, number>
  notifBusy: boolean
  tokenEdits: Record<string, string>
  setTokenEdits: (v: Record<string, string> | ((prev: Record<string, string>) => Record<string, string>)) => void
  handleAddWatch: () => void
  handleUpdateWatchToken: (w: CommentWatch) => void
  handleToggleWatch: (w: CommentWatch) => void
  handleRemoveWatch: (id: string) => void
  handleRunWatch: (id: string) => void
  handleCollectNotifications: () => void
  handleRunAll: () => void
  timeStr: (t?: string) => string
  credentialName: (id?: string) => string
}

export function WatchMonitorCard({
  platform,
  watches,
  scheduler,
  sourceStatus,
  credentials,
  selectedCredential,
  setSelectedCredential,
  watchInput,
  setWatchInput,
  watchXsecToken,
  setWatchXsecToken,
  watchAutoAnalyze,
  setWatchAutoAnalyze,
  watchBusy,
  unread,
  notifBusy,
  tokenEdits,
  setTokenEdits,
  handleAddWatch,
  handleUpdateWatchToken,
  handleToggleWatch,
  handleRemoveWatch,
  handleRunWatch,
  handleCollectNotifications,
  handleRunAll,
  timeStr,
  credentialName,
}: WatchMonitorCardProps) {
  return (
    <>
      {/* 自动采集监控 */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
            ⏱️ 自动采集监控
            {scheduler && (
              <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
                {scheduler.scheduled ? `每 ${Math.max(1, Math.round(scheduler.collectMs / 60000))} 分钟自动采集` : '定时任务已关闭'}
                {' · '}监控 {scheduler.watchCount} 个作品
                {' · '}自动分析 {scheduler.autoAnalyze ? `开（单轮≤${scheduler.autoAnalyzeLimit}条）` : '关'}
                {' · '}单作品最小间隔 {scheduler.minIntervalSeconds}s
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            {Object.keys(unread).length > 0 && (
              <span className="text-xs" style={{ color: '#86909C' }}>
                未读 评论/@{unread.mentions ?? 0} · 赞{unread.likes ?? 0} · 关注{unread.connections ?? 0}
              </span>
            )}
            <button
              onClick={() => void handleCollectNotifications()}
              disabled={notifBusy}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
            >
              {notifBusy ? '拉取中…' : '📥 拉取评论通知'}
            </button>
            <button
              onClick={() => void handleRunAll()}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium"
              style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
            >
              ▶ 立即执行一轮
            </button>
          </div>
        </div>

        <div className="mb-3 flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>作品 ID</span>
            <input
              value={watchInput}
              onChange={(e) => setWatchInput(e.target.value)}
              placeholder="填入要长期监控的作品 ID"
              className="w-64 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>归属账号</span>
            <select
              value={selectedCredential}
              onChange={(e) => setSelectedCredential(e.target.value)}
              className="w-44 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全局配置 / 默认账号</option>
              {credentials.map((c) => (
                <option key={c.credentialId} value={c.credentialId}>
                  {c.accountName}{c.defaultCredential ? '（默认）' : ''}{c.enabled ? '' : '（停用）'}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>
              xsec_token（自建桥必填，会过期）
            </span>
            <input
              value={watchXsecToken}
              onChange={(e) => setWatchXsecToken(e.target.value)}
              placeholder="形如 AB1cD…；用第三方数据服务可留空"
              className="w-72 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
          </div>
          <label className="flex items-center gap-2 pb-2 text-xs" style={{ color: '#4E5969' }}>
            <input
              type="checkbox"
              checked={watchAutoAnalyze}
              onChange={(e) => setWatchAutoAnalyze(e.target.checked)}
            />
            新评论自动 AI 分析
          </label>
          <button
            onClick={() => void handleAddWatch()}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white"
            style={{ background: '#722ED1' }}
          >
            ＋ 加入监控
          </button>
        </div>

        {sourceStatus && (
          <div
            className="mb-3 rounded-lg px-3 py-2 text-xs"
            style={{
              background: sourceStatus.xiaohongshuConfigured ? '#E8FFEA' : '#FFF7E8',
              color: sourceStatus.xiaohongshuConfigured ? '#00782C' : '#9C5B00',
            }}
          >
            数据源：
            {sourceStatus.configuredSource === 'auto'
              ? '真实接口优先（不可用时回退模拟）'
              : sourceStatus.configuredSource === 'api'
                ? '仅真实接口（失败即报错）'
                : '仅模拟数据'}
            {sourceStatus.xiaohongshuConfigured
              ? ` · 小红书真实接口已配置（${sourceStatus.xiaohongshuAuthMode}）`
              : ` · 小红书真实接口未配置：${sourceStatus.hint}`}
          </div>
        )}

        {scheduler?.lastRun && (
          <div className="mb-3 text-xs" style={{ color: '#86909C' }}>
            上次运行：{timeStr(scheduler.lastRun.startedAt)}（{scheduler.lastRun.triggerType === 'schedule' ? '定时' : '手动'}）
            {' · '}作品 {scheduler.lastRun.worksScanned}
            {' · '}抓取 {scheduler.lastRun.commentsCollected}
            {' · '}新增 {scheduler.lastRun.commentsNew}
            {' · '}分析 {scheduler.lastRun.analyzed}
            {scheduler.lastRun.failed > 0 ? ` · 失败 ${scheduler.lastRun.failed}` : ''}
          </div>
        )}

        <div className="space-y-2">
          {watches.length === 0 && (
            <div className="text-xs" style={{ color: '#86909C' }}>
              还没有监控作品。把作品 ID 加入监控后，定时任务会持续抓取新评论并（可选）自动分析。
            </div>
          )}
          {watches.map((w) => (
            <div
              key={w.watchId}
              className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-xs"
              style={{ borderColor: '#F2F3F5' }}
            >
              <span className="font-medium" style={{ color: '#1D2129' }}>{w.workId}</span>
              <span className="rounded px-2 py-0.5" style={{ background: '#F2F3F5', color: '#4E5969' }}>{w.platform}</span>
              <span
                className="rounded px-2 py-0.5"
                style={{
                  background: w.enabled ? '#E8FFEA' : '#F2F3F5',
                  color: w.enabled ? '#00782C' : '#86909C',
                }}
              >
                {w.enabled ? '监控中' : '已暂停'}
              </span>
              {w.autoAnalyze && (
                <span className="rounded px-2 py-0.5" style={{ background: '#F5E8FF', color: '#722ED1' }}>
                  自动分析
                </span>
              )}
              {w.lastSource && (
                <span className="rounded px-2 py-0.5" style={{ background: '#F2F3F5', color: '#4E5969' }}>
                  上次源 {w.lastSource}
                </span>
              )}
              <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>
                账号 {credentialName(w.credentialId)}
              </span>
              {w.tokenState === 'EXPIRED' && (
                <span className="rounded px-2 py-0.5" style={{ background: '#FFECE8', color: '#F53F3F' }}>
                  ⚠️ 需更新票据（xsec_token 已失效{w.observedTtlDays ? `，观测有效期 ${w.observedTtlDays} 天` : ''}）
                </span>
              )}
              {w.tokenState !== 'EXPIRED' && w.tokenWarning === 'EXPIRING' && (
                <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>
                  ⏳ 票据即将过期（已用 {w.tokenAgeDays ?? '?'} 天，建议更新）
                </span>
              )}
              <span style={{ color: '#86909C' }}>
                累计 {w.totalCollected} 条 · 上次新增 {w.lastNewCount} ·{' '}
                {w.lastCollectedAt ? timeStr(w.lastCollectedAt) : '尚未采集'}
              </span>
              {w.lastError && <span style={{ color: '#F53F3F' }}>⚠️ {w.lastError}</span>}
              <span className="ml-auto flex gap-2">
                {(w.tokenState === 'EXPIRED' || w.tokenWarning === 'EXPIRING') && (
                  <>
                    <input
                      value={tokenEdits[w.watchId] ?? ''}
                      onChange={(e) => setTokenEdits((prev) => ({ ...prev, [w.watchId]: e.target.value }))}
                      placeholder="粘贴新的 xsec_token"
                      className="w-52 rounded-lg border px-2 py-1 outline-none"
                      style={{ borderColor: '#F53F3F' }}
                    />
                    <button
                      onClick={() => void handleUpdateWatchToken(w)}
                      className="rounded-lg border px-2 py-1"
                      style={{ borderColor: '#F53F3F', color: '#F53F3F' }}
                    >
                      更新票据
                    </button>
                  </>
                )}
                <button
                  onClick={() => void handleRunWatch(w.watchId)}
                  disabled={watchBusy === w.watchId}
                  className="rounded-lg border px-2 py-1 disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
                >
                  {watchBusy === w.watchId ? '采集中…' : '立即采集'}
                </button>
                <button
                  onClick={() => void handleToggleWatch(w)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#FF7D00' }}
                >
                  {w.enabled ? '暂停' : '启用'}
                </button>
                <button
                  onClick={() => void handleRemoveWatch(w.watchId)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#F53F3F' }}
                >
                  移除
                </button>
              </span>
            </div>
          ))}
        </div>
      </div>
    </>
  )
}