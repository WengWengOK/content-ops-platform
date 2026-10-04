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

/** CommentChatPanel：由 CommentsPage 拆分出的独立模块，数据与操作通过 props 注入。 */
export interface CommentChatPanelProps {
  chatComment: PlatformComment | null
  setChatComment: (c: PlatformComment | null) => void
  chatInput: string
  setChatInput: (v: string) => void
  chatBusy: boolean
  dialogTurns: DialogTurn[]
  editReply: string
  setEditReply: (v: string) => void
  handleChatSend: () => void
  handleSaveReply: () => void
  handleApprove: (id: string) => void
  handleSend: (id: string) => void
}

export function CommentChatPanel({
  chatComment,
  setChatComment,
  chatInput,
  setChatInput,
  chatBusy,
  dialogTurns,
  editReply,
  setEditReply,
  handleChatSend,
  handleSaveReply,
  handleApprove,
  handleSend,
}: CommentChatPanelProps) {
  return (
    <>
      {/* AI 对话面板 */}
      {chatComment && (
        <div className="mt-4 rounded-xl border bg-white p-4 shadow-sm" style={{ borderColor: '#E5E6EB' }}>
          <div className="mb-3 flex items-center justify-between">
            <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
              💬 与「{chatComment.author || '匿名用户'}」对话
            </div>
            <button onClick={() => setChatComment(null)} className="text-xs" style={{ color: '#86909C' }}>
              关闭
            </button>
          </div>
          <div className="mb-3 rounded-lg px-3 py-2 text-xs" style={{ background: '#F7F8FA', color: '#4E5969' }}>
            原评论：{chatComment.content}
          </div>
          <div className="mb-3 max-h-64 space-y-2 overflow-y-auto rounded-lg border p-3" style={{ borderColor: '#F2F3F5' }}>
            {dialogTurns.length === 0 && (
              <div className="text-xs" style={{ color: '#86909C' }}>还没有对话，发送第一条消息让 AI 生成回复草稿</div>
            )}
            {dialogTurns.map((t, i) => (
              <div key={i} className={`flex ${t.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[75%] rounded-xl px-3 py-2 text-sm ${
                    t.role === 'user' ? 'text-white' : ''
                  }`}
                  style={
                    t.role === 'user'
                      ? { background: '#FF2D5E' }
                      : { background: '#F2F3F5', color: '#1D2129' }
                  }
                >
                  {t.content}
                </div>
              </div>
            ))}
            {chatBusy && (
              <div className="text-xs" style={{ color: '#86909C' }}>AI 思考中…</div>
            )}
          </div>
          <div className="mb-3 flex gap-2">
            <input
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void handleChatSend() }}
              placeholder="输入想和评论用户沟通的内容…"
              className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
            <button
              onClick={() => void handleChatSend()}
              disabled={chatBusy}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              style={{ background: '#722ED1' }}
            >
              发送
            </button>
          </div>
          <div className="flex items-center gap-2">
            <input
              value={editReply}
              onChange={(e) => setEditReply(e.target.value)}
              placeholder="人工修改 AI 回复草稿…"
              className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
            <button
              onClick={() => void handleSaveReply()}
              className="rounded-lg border px-4 py-2 text-sm font-medium"
              style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
            >
              保存草稿
            </button>
            <button
              onClick={() => void handleApprove(chatComment.commentId)}
              disabled={chatComment.replyStatus !== 'DRAFT'}
              className="rounded-lg border px-4 py-2 text-sm font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#00B42A' }}
            >
              审核通过
            </button>
            <button
              onClick={() => void handleSend(chatComment.commentId)}
              disabled={chatComment.replyStatus !== 'APPROVED'}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
              style={{ background: '#FF2D5E' }}
            >
              发送回复
            </button>
          </div>
        </div>
      )}
    </>
  )
}