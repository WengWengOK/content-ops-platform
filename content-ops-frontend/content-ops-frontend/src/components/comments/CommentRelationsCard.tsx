import { useCallback, useEffect, useState } from 'react'
import { getCommentRelations, setCommentUserRelation, syncCommentFollowers } from '@/api/comments'
import type { CommentRelationOverview } from '@/types'

const RELATION_LABEL: Record<string, string> = {
  FAN: '粉丝',
  FOLLOWING: '关注',
  FRIEND: '好友',
  REGULAR: '常客',
  SELF: '自己',
  STRANGER: '路人',
}

const RELATION_COLOR: Record<string, string> = {
  FAN: '#FF2D5E',
  FOLLOWING: '#165DFF',
  FRIEND: '#722ED1',
  REGULAR: '#FF7D00',
  SELF: '#00B42A',
  STRANGER: '#86909C',
}

/**
 * 评论者关系标签：粉丝（来自「新增关注」通知，自动维护）、关注/好友（人工或外部关系源）、
 * 常客（按评论次数自动判定）、自己、路人。
 */
export function CommentRelationsCard({ onChanged }: { onChanged?: () => void }) {
  const [overview, setOverview] = useState<CommentRelationOverview | null>(null)
  const [relationFilter, setRelationFilter] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setOverview(await getCommentRelations(relationFilter || undefined, 50))
    } catch {
      setOverview(null)
    }
  }, [relationFilter])

  useEffect(() => {
    void load()
  }, [load])

  const handleSyncFollowers = async () => {
    setBusy(true)
    try {
      const marked = await syncCommentFollowers()
      setMessage(`已同步「新增关注」：更新 ${marked} 位粉丝标签`)
      await load()
      onChanged?.()
    } catch (err: any) {
      setMessage(err?.message || '同步失败')
    } finally {
      setBusy(false)
    }
  }

  const handleSetRelation = async (userKey: string, relation: string) => {
    try {
      await setCommentUserRelation(userKey, relation)
      setMessage(`已把 ${userKey} 标记为「${RELATION_LABEL[relation] ?? relation}」`)
      await load()
      onChanged?.()
    } catch (err: any) {
      setMessage(err?.message || '设置失败')
    }
  }

  return (
    <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
          👥 评论者关系
          {overview && (
            <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
              常客阈值 {overview.regularThreshold} 次 · 粉丝由「新增关注」通知自动维护
            </span>
          )}
        </div>
        <button
          onClick={() => void handleSyncFollowers()}
          disabled={busy}
          className="rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-40"
          style={{ borderColor: '#E5E6EB', color: '#FF2D5E' }}
        >
          {busy ? '同步中…' : '🔄 同步粉丝标签'}
        </button>
      </div>

      <div className="mb-3 flex flex-wrap gap-2 text-xs">
        {overview?.byRelation?.map((r) => (
          <span
            key={r.relation ?? 'UNKNOWN'}
            className="rounded-full px-3 py-1"
            style={{ background: '#F7F8FA', color: RELATION_COLOR[r.relation ?? ''] ?? '#4E5969' }}
          >
            {RELATION_LABEL[r.relation ?? ''] ?? r.relation ?? '未标记'} · {r.cnt}
          </span>
        ))}
        {(!overview || (overview.byRelation?.length ?? 0) === 0) && (
          <span style={{ color: '#86909C' }}>还没有评论者数据，采集评论后自动归类</span>
        )}
      </div>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <select
          value={relationFilter}
          onChange={(e) => setRelationFilter(e.target.value)}
          className="w-32 rounded-lg border px-3 py-1.5 text-xs outline-none"
          style={{ borderColor: '#E5E6EB' }}
        >
          <option value="">全部关系</option>
          {Object.entries(RELATION_LABEL).map(([key, label]) => (
            <option key={key} value={key}>{label}</option>
          ))}
        </select>
        {message && <span className="text-xs" style={{ color: '#165DFF' }}>{message}</span>}
      </div>

      <div className="max-h-56 space-y-1 overflow-y-auto">
        {overview?.users?.map((user) => (
          <div key={user.user_key} className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-1.5 text-xs"
               style={{ borderColor: '#F2F3F5' }}>
            <span className="font-medium" style={{ color: '#1D2129' }}>{user.nickname || user.user_key}</span>
            <span className="rounded px-2 py-0.5"
                  style={{ background: '#F7F8FA', color: RELATION_COLOR[user.relation ?? ''] ?? '#4E5969' }}>
              {RELATION_LABEL[user.relation ?? ''] ?? user.relation ?? '未标记'}
            </span>
            <span style={{ color: '#86909C' }}>
              评论 {user.comment_count ?? 0} 次 · 来源 {user.relation_source ?? '-'}
            </span>
            <span className="ml-auto flex gap-2">
              <button
                onClick={() => void handleSetRelation(user.user_key, 'FAN')}
                className="rounded-lg border px-2 py-0.5"
                style={{ borderColor: '#E5E6EB', color: '#FF2D5E' }}
              >
                粉丝
              </button>
              <button
                onClick={() => void handleSetRelation(user.user_key, 'FOLLOWING')}
                className="rounded-lg border px-2 py-0.5"
                style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
              >
                关注
              </button>
              <button
                onClick={() => void handleSetRelation(user.user_key, 'FRIEND')}
                className="rounded-lg border px-2 py-0.5"
                style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
              >
                好友
              </button>
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}