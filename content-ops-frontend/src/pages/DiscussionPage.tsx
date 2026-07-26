import { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import { useDiscussion } from '@/hooks/useWorkflow'
import { ErrorView } from '@/components/common/StateViews'
import { trackWorkflow } from '@/utils/workflowTracker'
import type { AccountProfile } from '@/types'

/* ============================================================
   Types
   ============================================================ */
interface DiscussionLocationState {
  accountProfile: AccountProfile
  fuzzyIdea: string
}

/* ============================================================
   Component
   ============================================================ */
export function DiscussionPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const locationState = location.state as DiscussionLocationState | null

  const {
    messages,
    loading,
    error,
    canFinalize,
    start,
    send,
    finalize,
  } = useDiscussion()

  const [inputValue, setInputValue] = useState('')
  const [finalizing, setFinalizing] = useState(false)
  const [autoStarted, setAutoStarted] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  /* ── Auto-start discussion when page loads ── */
  useEffect(() => {
    if (autoStarted || !locationState?.accountProfile || !locationState?.fuzzyIdea) return
    setAutoStarted(true)
    start({
      fuzzyIdea: locationState.fuzzyIdea,
      accountProfile: locationState.accountProfile,
    })
  }, [autoStarted, locationState, start])

  /* ── Auto-scroll to bottom on new messages ── */
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  /* ── Handlers ── */
  const handleSend = useCallback(async () => {
    if (!inputValue.trim() || loading) return
    const msg = inputValue.trim()
    setInputValue('')
    await send(msg)
  }, [inputValue, loading, send])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const handleFinalize = useCallback(async () => {
    setFinalizing(true)
    const workflowId = await finalize(true)
    if (workflowId) {
      trackWorkflow(workflowId, locationState?.fuzzyIdea)
      navigate(`/workflow-detail?workflowId=${workflowId}`)
    }
    setFinalizing(false)
  }, [finalize, navigate, locationState])

  /* ── Guard: no location state ── */
  if (!locationState?.accountProfile || !locationState?.fuzzyIdea) {
    return (
      <Layout activeNav="create" pageTitle="讨论模式">
        <ErrorView
          message="缺少账号信息或创作方向，请返回创建工作流页面重新填写"
          onRetry={() => navigate('/create-workflow')}
        />
      </Layout>
    )
  }

  /* ── Derive phase label ── */
  const phaseLabel = canFinalize ? '已可确认' : '讨论中'

  return (
    <Layout
      activeNav="create"
      breadcrumbs={[
        { label: '创建工作流', href: '/create-workflow' },
        { label: '讨论模式' },
      ]}
    >
      {/* ── Header ── */}
      <div className="mb-6" style={{ animation: 'fadeInUp 300ms ease both' }}>
        <div className="flex items-center justify-between">
          <div>
            <h1 className="font-semibold" style={{ color: '#1D2129', fontSize: 16 }}>
              讨论模式
            </h1>
            <p className="mt-1" style={{ fontSize: 14, color: '#86909C' }}>
              与 AI 讨论你的创作想法，明确方向后再生成方案并启动流水线
            </p>
          </div>
          <div className="flex items-center gap-2">
            <span
              className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium"
              style={{
                color: canFinalize ? '#00B42A' : '#165DFF',
                background: canFinalize ? '#E8F8F0' : '#E8F3FF',
              }}
            >
              {phaseLabel}
            </span>
          </div>
        </div>
      </div>

      {/* ── Chat Container ── */}
      <div
        className="bg-white rounded-lg border border-[#E5E6EB] flex flex-col"
        style={{
          height: 'calc(100vh - 220px)',
          minHeight: 400,
          animation: 'fadeInUp 400ms ease both',
        }}
      >
        {/* Messages area */}
        <div
          className="flex-1 overflow-y-auto p-6"
          style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
        >
          {messages.length === 0 && !loading && (
            <div className="flex items-center justify-center h-full">
              <span style={{ color: '#86909C', fontSize: 14 }}>正在启动讨论...</span>
            </div>
          )}

          {messages.map((msg, i) => (
            <div
              key={i}
              className="flex"
              style={{
                justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
              }}
            >
              <div
                className="max-w-[70%] rounded-lg px-4 py-3"
                style={{
                  background: msg.role === 'user' ? '#165DFF' : '#F7F8FA',
                  color: msg.role === 'user' ? '#fff' : '#1D2129',
                  fontSize: 14,
                  lineHeight: 1.6,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {msg.content}
              </div>
            </div>
          ))}

          {loading && (
            <div className="flex" style={{ justifyContent: 'flex-start' }}>
              <div
                className="rounded-lg px-4 py-3"
                style={{
                  background: '#F7F8FA',
                  color: '#86909C',
                  fontSize: 14,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <span
                  style={{
                    width: 16,
                    height: 16,
                    border: '2px solid #E5E6EB',
                    borderTopColor: '#165DFF',
                    borderRadius: '50%',
                    display: 'inline-block',
                    animation: 'spin 0.8s linear infinite',
                  }}
                />
                AI 正在思考...
              </div>
            </div>
          )}

          {error && (
            <div
              className="rounded-lg px-4 py-3 self-center"
              style={{
                background: '#FFF2F0',
                border: '1px solid #FFCCC7',
                color: '#F53F3F',
                fontSize: 14,
              }}
            >
              {error}
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Input area */}
        <div
          className="border-t border-[#E5E6EB] p-4"
          style={{ display: 'flex', gap: 12, alignItems: 'flex-end' }}
        >
          <textarea
            className="input-field"
            placeholder="输入你的想法或问题..."
            style={{
              flex: 1,
              minHeight: 44,
              maxHeight: 120,
              resize: 'none',
              fontFamily: 'inherit',
            }}
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={loading}
          />
          <button
            type="button"
            className="btn-primary px-5 py-2 rounded-lg text-sm font-medium text-white"
            onClick={handleSend}
            disabled={!inputValue.trim() || loading}
            style={{
              opacity: !inputValue.trim() || loading ? 0.5 : 1,
              cursor: !inputValue.trim() || loading ? 'not-allowed' : 'pointer',
            }}
          >
            发送
          </button>
        </div>

        {/* Finalize bar */}
        {canFinalize && (
          <div
            className="border-t border-[#E5E6EB] px-4 py-3 flex items-center justify-between"
            style={{ background: '#F7F8FA', animation: 'fadeInUp 300ms ease both' }}
          >
            <span style={{ fontSize: 13, color: '#4E5969' }}>
              ✓ 方向已明确，可以确认并启动流水线
            </span>
            <button
              type="button"
              className="btn-primary px-5 py-2 rounded-lg text-sm font-medium text-white"
              onClick={handleFinalize}
              disabled={finalizing}
            >
              {finalizing ? '正在启动...' : '确认并启动流水线'}
            </button>
          </div>
        )}
      </div>

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </Layout>
  )
}
