import { useState, useRef, useEffect } from 'react'
import { MessageSquareText, Send, Sparkles, CheckCircle2, RefreshCw } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Loading } from '@/components/ui/Loading'
import { useDiscussionStore } from '@/store/discussionStore'
import { DISCUSSION_PHASE_META } from '@/utils/constants'
import { useNavigate } from 'react-router-dom'

export function DiscussionPage() {
  const navigate = useNavigate()
  const {
    session, lastResponse, loading, error, fuzzyIdea,
    setFuzzyIdea, startDiscussion, chat, finalize, clearSession, reset,
  } = useDiscussionStore()
  const [input, setInput] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)

  const turns = session?.turns || []
  const phase = lastResponse?.phase || session?.phase
  const phaseMeta = phase ? DISCUSSION_PHASE_META[phase] : null

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [turns.length])

  const handleStart = () => startDiscussion()
  const handleSend = () => {
    if (!input.trim()) return
    chat(input)
    setInput('')
  }
  const handleFinalize = async () => {
    const workflowId = await finalize(true)
    if (workflowId) {
      navigate('/workflow')
    }
  }
  const handleClear = () => {
    clearSession()
    reset()
  }

  return (
    <div className="flex h-[calc(100vh-7rem)] flex-col animate-fade-in">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">讨论式选题</h1>
          <p className="text-sm text-gray-500">通过自然语言对话与AI共同打磨内容方向</p>
        </div>
        {session && (
          <div className="flex items-center gap-2">
            {phaseMeta && <Badge variant="info">{phaseMeta.label}</Badge>}
            <Button variant="ghost" size="sm" onClick={handleClear}>
              <RefreshCw className="h-4 w-4" /> 新对话
            </Button>
          </div>
        )}
      </div>

      {!session ? (
        <Card className="flex flex-1 flex-col">
          <CardContent className="flex flex-1 flex-col items-center justify-center pt-6">
            <div className="w-full max-w-lg space-y-4">
              <div className="text-center">
                <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-amber-100">
                  <MessageSquareText className="h-6 w-6 text-amber-600" />
                </div>
                <h2 className="text-lg font-semibold text-gray-900">开始一段讨论</h2>
                <p className="mt-1 text-sm text-gray-500">
                  告诉我你模糊的想法，我会通过提问帮你逐步明确选题方向
                </p>
              </div>
              <textarea
                value={fuzzyIdea}
                onChange={(e) => setFuzzyIdea(e.target.value)}
                placeholder="例如：想写一些关于职场新人适应期的内容，但不确定从什么角度切入..."
                rows={4}
                className="w-full rounded-lg border border-gray-300 px-4 py-3 text-sm focus:border-amber-500 focus:outline-none focus:ring-1 focus:ring-amber-500"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleStart()
                }}
              />
              {error && <p className="text-sm text-red-500">{error}</p>}
              <div className="flex justify-center">
                <Button onClick={handleStart} loading={loading} size="lg">
                  <Sparkles className="h-5 w-5" />
                  开始讨论
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      ) : (
        <Card className="flex flex-1 flex-col overflow-hidden">
          <div className="flex-1 space-y-4 overflow-y-auto p-6 scrollbar-thin">
            {turns.map((turn, i) => (
              <div key={i} className={`flex ${turn.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[75%] rounded-2xl px-4 py-2.5 ${
                  turn.role === 'user'
                    ? 'bg-brand-600 text-white'
                    : 'bg-gray-100 text-gray-800'
                }`}>
                  <p className="text-sm whitespace-pre-wrap">{turn.content}</p>
                </div>
              </div>
            ))}

            {loading && (
              <div className="flex justify-start">
                <div className="rounded-2xl bg-gray-100 px-4 py-2.5">
                  <Loading size="sm" text="AI 正在思考..." />
                </div>
              </div>
            )}

            {lastResponse?.clarifyingQuestions && lastResponse.clarifyingQuestions.length > 0 && !loading && (
              <div className="rounded-lg border border-blue-200 bg-blue-50 p-3">
                <p className="mb-2 text-xs font-semibold text-blue-700">需要澄清的问题</p>
                <ul className="space-y-1">
                  {lastResponse!.clarifyingQuestions.map((q, i) => (
                    <li key={i} className="text-sm text-blue-600">• {q}</li>
                  ))}
                </ul>
              </div>
            )}

            {lastResponse?.proposedDirections && lastResponse.proposedDirections.length > 0 && !loading && (
              <div className="rounded-lg border border-purple-200 bg-purple-50 p-3">
                <p className="mb-2 text-xs font-semibold text-purple-700">建议的方向</p>
                <ul className="space-y-1">
                  {lastResponse!.proposedDirections.map((d, i) => (
                    <li key={i} className="text-sm text-purple-600">{i + 1}. {d}</li>
                  ))}
                </ul>
              </div>
            )}

            {lastResponse?.canFinalize && !loading && (
              <div className="flex justify-center">
                <Button onClick={handleFinalize} variant="primary" size="sm">
                  <CheckCircle2 className="h-4 w-4" />
                  确认方向并启动工作流
                </Button>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          <div className="border-t border-gray-100 p-4">
            {error && <p className="mb-2 text-sm text-red-500">{error}</p>}
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSend()}
                placeholder="输入你的回复..."
                className="flex-1 rounded-lg border border-gray-300 px-4 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                disabled={loading}
              />
              <Button onClick={handleSend} disabled={!input.trim() || loading} size="md">
                <Send className="h-4 w-4" />
                发送
              </Button>
            </div>
          </div>
        </Card>
      )}
    </div>
  )
}
