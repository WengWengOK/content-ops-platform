import { useCallback, useEffect, useState } from 'react'
import {
  activateReplyAgent,
  listReplyAgents,
  listReviewQueue,
  markCommentReviewed,
  previewReplyAgent,
  setReplyAgentEnabled,
  updateReplyAgent,
} from '@/api/comments'
import type { PlatformComment, ReplyAgentProfile, ReplyDraftPreview, ReplyFlagItem } from '@/types'

const SCENARIO_LABEL: Record<string, string> = {
  ECOMMERCE: '电商带货',
  COURSE: '课程客服',
  GENERIC: '通用客服',
}

const LEVEL_COLOR: Record<string, string> = {
  HIGH: '#F53F3F',
  MEDIUM: '#FF9A2E',
  LOW: '#86909C',
}

/**
 * 场景客服回复 Agent：配置（可插拔场景）/ 试跑预览 / 忠实度校验复核清单。
 *
 * 设计原则：Agent 只产出草稿 + 自动标记，人工复核决定是否采纳；不做自动修正。
 * 定制新场景（如课程客服）只需新增一条配置并激活。
 */
export function ReplyAgentCard() {
  const [profiles, setProfiles] = useState<ReplyAgentProfile[]>([])
  const [queue, setQueue] = useState<PlatformComment[]>([])
  const [selected, setSelected] = useState('')
  const [previewText, setPreviewText] = useState('')
  const [productContext, setProductContext] = useState('')
  const [preview, setPreview] = useState<ReplyDraftPreview | null>(null)
  const [editingFacts, setEditingFacts] = useState<{ id: string; value: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  const load = useCallback(async () => {
    try {
      const list = await listReplyAgents()
      setProfiles(list)
      if (!selected && list.length > 0) {
        const preferred = list.find((p) => p.defaultProfile) ?? list[0]
        setSelected(preferred.profileId)
      }
    } catch {
      setProfiles([])
    }
    try {
      const res = await listReviewQueue('NEEDS_REVIEW', 20)
      setQueue(res.comments ?? [])
    } catch {
      setQueue([])
    }
  }, [selected])

  useEffect(() => {
    void load()
  }, [load])

  const handlePreview = async () => {
    if (!previewText.trim()) {
      setMessage('请先粘贴一条评论')
      return
    }
    setBusy(true)
    try {
      setPreview(
        await previewReplyAgent({
          profileId: selected || undefined,
          commentContent: previewText.trim(),
          productContext: productContext.trim() || undefined,
        })
      )
      setMessage('')
    } catch (err: any) {
      setMessage(err?.message || '试跑失败')
    } finally {
      setBusy(false)
    }
  }

  const handleActivate = async (profileId: string) => {
    try {
      await activateReplyAgent(profileId)
      await load()
      setMessage('已设为默认场景（未指定场景时使用）')
    } catch (err: any) {
      setMessage(err?.message || '激活失败')
    }
  }

  const handleToggle = async (profile: ReplyAgentProfile) => {
    try {
      await setReplyAgentEnabled(profile.profileId, !profile.enabled)
      await load()
    } catch (err: any) {
      setMessage(err?.message || '操作失败')
    }
  }

  const handleSaveFacts = async () => {
    if (!editingFacts) return
    try {
      await updateReplyAgent(editingFacts.id, { productFacts: editingFacts.value })
      setEditingFacts(null)
      await load()
      setMessage('商品/业务事实已更新，后续草稿只会依据这些事实回答')
    } catch (err: any) {
      setMessage(err?.message || '保存失败')
    }
  }

  const handleReviewed = async (commentId: string) => {
    try {
      await markCommentReviewed(commentId)
      await load()
      setMessage('已标记复核完成')
    } catch (err: any) {
      setMessage(err?.message || '标记失败')
    }
  }

  const parseFlags = (raw?: string): ReplyFlagItem[] => {
    if (!raw) return []
    try {
      const arr = JSON.parse(raw)
      return Array.isArray(arr) ? arr : []
    } catch {
      return []
    }
  }

  return (
    <div className="space-y-4">
      {/* 场景配置 */}
      <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 text-sm font-medium" style={{ color: '#1D2129' }}>
          🤖 场景客服回复 Agent
          <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
            共 {profiles.length} 个场景 · 草稿由场景配置生成，只做自动标记，人工复核后发送
          </span>
        </div>
        <div className="space-y-2">
          {profiles.map((p) => (
            <div key={p.profileId} className="rounded-lg border px-3 py-2 text-xs" style={{ borderColor: '#F2F3F5' }}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium" style={{ color: '#1D2129' }}>{p.name}</span>
                <span className="rounded px-2 py-0.5" style={{ background: '#F2F3F5', color: '#4E5969' }}>
                  {SCENARIO_LABEL[p.scenario] ?? p.scenario}
                </span>
                {p.defaultProfile && (
                  <span className="rounded px-2 py-0.5" style={{ background: '#E8F3FF', color: '#165DFF' }}>默认</span>
                )}
                <span className="rounded px-2 py-0.5"
                      style={{ background: p.enabled ? '#E8FFEA' : '#F2F3F5', color: p.enabled ? '#00782C' : '#86909C' }}>
                  {p.enabled ? '启用' : '停用'}
                </span>
                <span style={{ color: '#86909C' }}>
                  长度上限 {p.replyMaxChars ?? 120} 字 · {p.allowLinks ? '允许链接' : '不发链接'} ·
                  禁止承诺：{p.forbiddenClaims || '（未配置）'}
                </span>
                <span className="ml-auto flex gap-2">
                  <button onClick={() => setEditingFacts({ id: p.profileId, value: p.productFacts ?? '' })}
                          className="rounded-lg border px-2 py-0.5" style={{ borderColor: '#E5E6EB', color: '#165DFF' }}>
                    编辑事实
                  </button>
                  <button onClick={() => void handleActivate(p.profileId)} disabled={p.defaultProfile}
                          className="rounded-lg border px-2 py-0.5 disabled:opacity-40"
                          style={{ borderColor: '#E5E6EB', color: '#722ED1' }}>
                    设为默认
                  </button>
                  <button onClick={() => void handleToggle(p)}
                          className="rounded-lg border px-2 py-0.5" style={{ borderColor: '#E5E6EB', color: '#FF7D00' }}>
                    {p.enabled ? '停用' : '启用'}
                  </button>
                </span>
              </div>
              {editingFacts?.id === p.profileId && (
                <div className="mt-2 flex flex-col gap-2">
                  <textarea value={editingFacts.value} rows={4}
                            onChange={(e) => setEditingFacts({ id: p.profileId, value: e.target.value })}
                            placeholder="商品/业务事实：价格、优惠、发货、退换、规格…（模型只能依据这些事实回答）"
                            className="w-full rounded-lg border px-3 py-2 outline-none"
                            style={{ borderColor: '#E5E6EB' }} />
                  <div className="flex gap-2">
                    <button onClick={() => void handleSaveFacts()}
                            className="rounded-lg px-3 py-1 text-white" style={{ background: '#165DFF' }}>
                      保存事实
                    </button>
                    <button onClick={() => setEditingFacts(null)}
                            className="rounded-lg border px-3 py-1" style={{ borderColor: '#E5E6EB', color: '#4E5969' }}>
                      取消
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* 试跑预览 */}
      <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 text-sm font-medium" style={{ color: '#1D2129' }}>
          🧪 试跑预览（不落库，用于定制场景）
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <select value={selected} onChange={(e) => setSelected(e.target.value)}
                  className="w-44 rounded-lg border px-3 py-2 text-sm outline-none" style={{ borderColor: '#E5E6EB' }}>
            {profiles.map((p) => (
              <option key={p.profileId} value={p.profileId}>{p.name}</option>
            ))}
          </select>
          <input value={previewText} onChange={(e) => setPreviewText(e.target.value)}
                 placeholder="粘贴一条评论，如「多少钱？能便宜点吗」"
                 className="w-80 rounded-lg border px-3 py-2 text-sm outline-none" style={{ borderColor: '#E5E6EB' }} />
          <input value={productContext} onChange={(e) => setProductContext(e.target.value)}
                 placeholder="临时商品事实（可选），如「售价 199 元，满 2 件 9 折」"
                 className="w-80 rounded-lg border px-3 py-2 text-sm outline-none" style={{ borderColor: '#E5E6EB' }} />
          <button onClick={() => void handlePreview()} disabled={busy}
                  className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                  style={{ background: '#722ED1' }}>
            {busy ? '生成中…' : '试跑'}
          </button>
        </div>
        {message && <div className="mt-2 text-xs" style={{ color: '#165DFF' }}>{message}</div>}
        {preview && (
          <div className="mt-3 rounded-lg p-3" style={{ background: '#F7F8FA' }}>
            <div className="mb-1 text-xs" style={{ color: '#86909C' }}>
              场景 {SCENARIO_LABEL[preview.scenario ?? ''] ?? preview.scenario} · 来源 {preview.source} ·
              置信度 {preview.confidence} · 复核状态 {preview.reviewState}
            </div>
            <div className="text-sm" style={{ color: '#1D2129' }}>{preview.reply}</div>
            {preview.flags.length > 0 && (
              <div className="mt-2 flex flex-col gap-1">
                {preview.flags.map((f, i) => (
                  <span key={i} className="text-xs" style={{ color: LEVEL_COLOR[f.level] ?? '#4E5969' }}>
                    [{f.level}] {f.message}
                  </span>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* 复核清单 */}
      <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 text-sm font-medium" style={{ color: '#1D2129' }}>
          📋 待复核清单（高置信度自动标记）
          <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
            {queue.length} 条 · 系统只标记不修正，请人工判断后处理
          </span>
        </div>
        <div className="space-y-2">
          {queue.length === 0 && (
            <div className="text-xs" style={{ color: '#86909C' }}>暂无高置信度标记，评论区很干净</div>
          )}
          {queue.map((c) => (
            <div key={c.commentId} className="rounded-lg border px-3 py-2 text-xs" style={{ borderColor: '#F2F3F5' }}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium" style={{ color: '#1D2129' }}>{c.author}</span>
                <span style={{ color: '#86909C' }}>置信度 {c.replyConfidence ?? '-'}</span>
                <span className="ml-auto flex gap-2">
                  <button onClick={() => void handleReviewed(c.commentId)}
                          className="rounded-lg border px-2 py-0.5" style={{ borderColor: '#E5E6EB', color: '#00B42A' }}>
                    标记已复核
                  </button>
                </span>
              </div>
              <div className="mt-1" style={{ color: '#4E5969' }}>评论：{c.content}</div>
              <div className="mt-1" style={{ color: '#1D2129' }}>草稿：{c.aiReply || '（无）'}</div>
              {parseFlags(c.replyFlags).map((f, i) => (
                <div key={i} style={{ color: LEVEL_COLOR[f.level] ?? '#4E5969' }}>[{f.level}] {f.message}</div>
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}