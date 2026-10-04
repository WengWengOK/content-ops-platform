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

/** CommentListPanel：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface CommentListPanelProps {
  comments: PlatformComment[]
  loading: boolean
  handleAnalyzeOne: (id: string) => void
  handleApprove: (id: string) => void
  handleSend: (id: string) => void
  openChat: (c: PlatformComment) => void
  timeStr: (t?: string) => string
}

export function CommentListPanel({
  comments,
  loading,
  handleAnalyzeOne,
  handleApprove,
  handleSend,
  openChat,
  timeStr,
}: CommentListPanelProps) {
  return (
    <>
      {/* 评论列表 */}
      <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 flex items-center justify-between">
          <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
            评论列表（{comments.length}）
          </div>
        </div>
        {loading && <div className="py-8 text-center text-sm" style={{ color: '#86909C' }}>加载中…</div>}
        {!loading && comments.length === 0 && (
          <div className="py-8 text-center text-sm" style={{ color: '#86909C' }}>
            暂无评论。输入作品 ID 后点击「采集评论」开始。
          </div>
        )}
        <div className="space-y-3">
          {comments.map((c) => (
            <div key={c.commentId} className="rounded-xl border p-4" style={{ borderColor: '#E5E6EB' }}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium" style={{ color: '#1D2129' }}>{c.author || '匿名用户'}</span>
                {c.intent && (
                  <span className="rounded px-2 py-0.5 text-xs font-medium"
                    style={{ background: '#FFF0F5', color: INTENT_COLORS[c.intent] ?? '#C40E3A' }}>
                    {c.intent}
                  </span>
                )}
                {c.sentiment && (
                  <span className="rounded px-2 py-0.5 text-xs font-medium"
                    style={{ background: '#F2F3F5', color: SENTIMENT_COLORS[c.sentiment] ?? '#4E5969' }}>
                    {c.sentiment}
                  </span>
                )}
                <span className="rounded px-2 py-0.5 text-xs"
                  style={{ background: '#F2F3F5', color: '#4E5969' }}>
                  {STATUS_CN[c.replyStatus ?? 'NONE'] ?? c.replyStatus}
                </span>
                {c.collectedVia && (
                  <span className="rounded px-2 py-0.5 text-xs"
                    style={{ background: c.collectedVia === 'notification' ? '#F5E8FF' : '#F2F3F5',
                             color: c.collectedVia === 'notification' ? '#722ED1' : '#86909C' }}>
                    {c.collectedVia === 'notification' ? '通知采集' : c.collectedVia === 'mock' ? '模拟' : '笔记采集'}
                  </span>
                )}
                <span className="text-xs" style={{ color: '#86909C' }}>
                  👍 {c.likes ?? 0} · {timeStr(c.commentTime)}
                </span>
              </div>
              <div className="mt-2 text-sm leading-relaxed" style={{ color: '#1D2129' }}>{c.content}</div>
              {c.replyTo && (
                <div className="mt-1 text-xs" style={{ color: '#86909C' }}>回复 @{c.replyTo}</div>
              )}
              {c.aiSummary && (
                <div className="mt-2 text-xs" style={{ color: '#4E5969' }}>AI 摘要：{c.aiSummary}</div>
              )}
              {c.aiReply && (
                <div className="mt-1 rounded-lg px-3 py-2 text-xs" style={{ background: '#F7F8FA', color: '#4E5969' }}>
                  AI 回复：{c.aiReply}
                </div>
              )}
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  onClick={() => void handleAnalyzeOne(c.commentId)}
                  disabled={c.intent !== undefined && c.intent !== ''}
                  className="rounded-lg border px-3 py-1 text-xs font-medium disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
                >
                  ✨ 分析
                </button>
                <button
                  onClick={() => openChat(c)}
                  className="rounded-lg border px-3 py-1 text-xs font-medium"
                  style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
                >
                  💬 AI 对话
                </button>
                <button
                  onClick={() => void handleApprove(c.commentId)}
                  disabled={c.replyStatus !== 'DRAFT'}
                  className="rounded-lg border px-3 py-1 text-xs font-medium disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#00B42A' }}
                >
                  ✅ 审核通过
                </button>
                <button
                  onClick={() => void handleSend(c.commentId)}
                  disabled={c.replyStatus !== 'APPROVED'}
                  className="rounded-lg border px-3 py-1 text-xs font-medium disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#FF2D5E' }}
                >
                  📤 发送回复
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </>
  )
}