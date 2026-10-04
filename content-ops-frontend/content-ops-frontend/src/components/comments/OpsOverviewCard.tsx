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

/** OpsOverviewCard：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface OpsOverviewCardProps {
  ops: OpsOverview | null
  opsBusy: boolean
  notifyBusy: boolean
  credentials: PlatformCredential[]
  handleRotateKeys: () => void
  handleNotifyTokenWarnings: () => void
  loadOps: () => void
}

export function OpsOverviewCard({
  ops,
  opsBusy,
  notifyBusy,
  credentials,
  handleRotateKeys,
  handleNotifyTokenWarnings,
  loadOps,
}: OpsOverviewCardProps) {
  return (
    <>
      {/* 运维总览：密钥轮换 / 账号健康 / 票据预警 */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
            🩺 运维总览
            {ops && (
              <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
                密钥 {ops.rotation.encryptionEnabled ? ops.rotation.keyId : '未启用加密'} · 待轮换 {ops.rotation.pendingRotation}
                {' · '}监控 {ops.watchHealth.enabled}/{ops.watchHealth.total} 启用
                {' · '}票据过期 {ops.watchHealth.expired} · 即将过期 {ops.watchHealth.expiringSoon}
                （阈值 {ops.watchHealth.warnDays} 天）
              </span>
            )}
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => void loadOps()}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium"
              style={{ borderColor: '#E5E6EB', color: '#4E5969' }}
            >
              刷新
            </button>
            <button
              onClick={() => void handleNotifyTokenWarnings()}
              disabled={notifyBusy}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
            >
              {notifyBusy ? '推送中…' : '🔔 立即推送预警'}
            </button>
            <button
              onClick={() => void handleRotateKeys()}
              disabled={opsBusy || !ops || ops.rotation.pendingRotation === 0}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
            >
              {opsBusy ? '轮换中…' : `🔐 执行密钥轮换（${ops?.rotation.pendingRotation ?? 0}）`}
            </button>
          </div>
        </div>

        {ops?.rotation.hint && (
          <div className="mb-3 rounded-lg px-3 py-2 text-xs" style={{ background: '#F7F8FA', color: '#4E5969' }}>
            轮换状态：{ops.rotation.hint}
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div>
            <div className="mb-2 text-xs font-medium" style={{ color: '#1D2129' }}>账号健康</div>
            <div className="space-y-1">
              {(!ops || ops.credentials.length === 0) && (
                <div className="text-xs" style={{ color: '#86909C' }}>暂无账号凭据</div>
              )}
              {ops?.credentials.map((c) => (
                <div
                  key={c.credentialId}
                  className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-1.5 text-xs"
                  style={{ borderColor: '#F2F3F5' }}
                >
                  <span className="font-medium" style={{ color: '#1D2129' }}>{c.accountName}</span>
                  {c.needsRotation && (
                    <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>待轮换</span>
                  )}
                  {(c.tokenState === 'AUTH_INVALID' || c.tokenState === 'ERROR') && (
                    <span className="rounded px-2 py-0.5" style={{ background: '#FFECE8', color: '#F53F3F' }}>需更新令牌</span>
                  )}
                  {c.tokenState === 'OK' && (
                    <span className="rounded px-2 py-0.5" style={{ background: '#E8FFEA', color: '#00782C' }}>已登录</span>
                  )}
                  <span style={{ color: '#86909C' }}>{c.baseUrl} · token {c.accessTokenMasked || '（未设置）'}</span>
                </div>
              ))}
            </div>
          </div>

          <div>
            <div className="mb-2 text-xs font-medium" style={{ color: '#1D2129' }}>票据预警（需要更新的监控作品）</div>
            <div className="space-y-1">
              {(!ops || ops.watchHealth.problems.length === 0) && (
                <div className="text-xs" style={{ color: '#86909C' }}>没有需要处理的票据</div>
              )}
              {ops?.watchHealth.problems.slice(0, 6).map((w) => (
                <div
                  key={w.watchId}
                  className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-1.5 text-xs"
                  style={{ borderColor: '#F2F3F5' }}
                >
                  <span className="font-medium" style={{ color: '#1D2129' }}>{w.workId}</span>
                  <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>{w.accountName}</span>
                  <span
                    className="rounded px-2 py-0.5"
                    style={w.warning === 'EXPIRED'
                      ? { background: '#FFECE8', color: '#F53F3F' }
                      : { background: '#FFF7E8', color: '#9C5B00' }}
                  >
                    {w.warning === 'EXPIRED' ? '已过期' : `即将过期（已用 ${w.tokenAgeDays ?? '?'} 天）`}
                  </span>
                  {w.observedTtlDays ? (
                    <span style={{ color: '#86909C' }}>观测有效期 {w.observedTtlDays} 天</span>
                  ) : null}
                </div>
              ))}
              {ops && ops.watchHealth.problems.length > 6 && (
                <div className="text-xs" style={{ color: '#86909C' }}>
                  还有 {ops.watchHealth.problems.length - 6} 条，见下方监控列表
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </>
  )
}