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

/** CredentialManagerCard：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface CredentialManagerCardProps {
  credentials: PlatformCredential[]
  credName: string
  setCredName: (v: string) => void
  credBaseUrl: string
  setCredBaseUrl: (v: string) => void
  credToken: string
  setCredToken: (v: string) => void
  credDefault: boolean
  setCredDefault: (v: boolean) => void
  credBusy: boolean
  probes: Record<string, CredentialProbeResult>
  handleCreateCredential: () => void
  handleProbeCredential: (id: string) => void
  handleSetDefaultCredential: (id: string) => void
  handleToggleCredential: (c: PlatformCredential) => void
  handleDeleteCredential: (id: string) => void
}

export function CredentialManagerCard({
  credentials,
  credName,
  setCredName,
  credBaseUrl,
  setCredBaseUrl,
  credToken,
  setCredToken,
  credDefault,
  setCredDefault,
  credBusy,
  probes,
  handleCreateCredential,
  handleProbeCredential,
  handleSetDefaultCredential,
  handleToggleCredential,
  handleDeleteCredential,
}: CredentialManagerCardProps) {
  return (
    <>
      {/* 平台账号凭据（多账号归属） */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 text-sm font-medium" style={{ color: '#1D2129' }}>
          🔑 平台账号凭据
          <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
            共 {credentials.length} 个账号 · 采集/回复/通知按账号归属，令牌加密存储且只显示掩码
          </span>
        </div>

        <div className="mb-3 flex flex-wrap items-end gap-3">
          <input
            value={credName}
            onChange={(e) => setCredName(e.target.value)}
            placeholder="账号名，如 主号·小红"
            className="w-40 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
          <input
            value={credBaseUrl}
            onChange={(e) => setCredBaseUrl(e.target.value)}
            placeholder="桥地址，如 http://127.0.0.1:18060"
            className="w-72 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
          <input
            value={credToken}
            onChange={(e) => setCredToken(e.target.value)}
            type="password"
            placeholder="桥的 AUTH_TOKEN"
            className="w-56 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
          <label className="flex items-center gap-2 pb-2 text-xs" style={{ color: '#4E5969' }}>
            <input type="checkbox" checked={credDefault} onChange={(e) => setCredDefault(e.target.checked)} />
            设为默认账号
          </label>
          <button
            onClick={() => void handleCreateCredential()}
            disabled={credBusy}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            style={{ background: '#165DFF' }}
          >
            {credBusy ? '添加中…' : '＋ 添加账号'}
          </button>
        </div>

        <div className="space-y-2">
          {credentials.length === 0 && (
            <div className="text-xs" style={{ color: '#86909C' }}>
              还没有账号凭据。添加后「加入监控」可选择该作品属于哪个账号；未配置时回退全局配置。
            </div>
          )}
          {credentials.map((c) => (
            <div
              key={c.credentialId}
              className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-xs"
              style={{ borderColor: '#F2F3F5' }}
            >
              <span className="font-medium" style={{ color: '#1D2129' }}>{c.accountName}</span>
              {c.defaultCredential && (
                <span className="rounded px-2 py-0.5" style={{ background: '#E8F3FF', color: '#165DFF' }}>默认</span>
              )}
              <span
                className="rounded px-2 py-0.5"
                style={{ background: c.enabled ? '#E8FFEA' : '#F2F3F5', color: c.enabled ? '#00782C' : '#86909C' }}
              >
                {c.enabled ? '启用' : '停用'}
              </span>
              <span style={{ color: '#86909C' }}>
                {c.baseUrl} · token {c.accessTokenMasked || '（未设置）'}
              </span>
              {probes[c.credentialId] && (
                <span style={{ color: probes[c.credentialId].authenticated ? '#00782C' : '#9C5B00' }}>
                  {probes[c.credentialId].hint}
                </span>
              )}
              {(c.tokenState === 'AUTH_INVALID' || c.tokenState === 'ERROR') && (
                <span className="rounded px-2 py-0.5" style={{ background: '#FFECE8', color: '#F53F3F' }}>
                  ⚠️ 需更新令牌（AUTH_TOKEN 失效或服务不可达）
                </span>
              )}
              {c.lastError && <span style={{ color: '#F53F3F' }}>⚠️ {c.lastError}</span>}
              <span className="ml-auto flex gap-2">
                <button
                  onClick={() => void handleProbeCredential(c.credentialId)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#0FC6C2' }}
                >
                  测试连接
                </button>
                <button
                  onClick={() => void handleSetDefaultCredential(c.credentialId)}
                  disabled={c.defaultCredential}
                  className="rounded-lg border px-2 py-1 disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
                >
                  设为默认
                </button>
                <button
                  onClick={() => void handleToggleCredential(c)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#FF7D00' }}
                >
                  {c.enabled ? '停用' : '启用'}
                </button>
                <button
                  onClick={() => void handleDeleteCredential(c.credentialId)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#F53F3F' }}
                >
                  删除
                </button>
              </span>
            </div>
          ))}
        </div>
      </div>
    </>
  )
}