import { useState } from 'react'
import { Bot, Lightbulb, PenLine, Image as ImageIcon, Send, BarChart3, RefreshCw, Play } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { useWorkflowStore } from '@/store/workflowStore'
import { agentApi } from '@/api/agents'

const agents = [
  { key: 'topic', name: '选题规划 Agent', icon: Lightbulb, color: '#f59e0b', port: 8081, desc: '分析热点趋势，生成选题候选', endpoint: '/topic/execute' },
  { key: 'content', name: '内容创作 Agent', icon: PenLine, color: '#3b82f6', port: 8082, desc: '生成大纲并撰写内容', endpoints: ['/content/outline', '/content/draft', '/content/execute'] },
  { key: 'image', name: '配图设计 Agent', icon: ImageIcon, color: '#8b5cf6', port: 8083, desc: '生成风格方向并创建配图', endpoints: ['/image/styles', '/image/generate', '/image/execute'] },
  { key: 'publish', name: '排版发布 Agent', icon: Send, color: '#10b981', port: 8084, desc: '多平台排版并发布', endpoint: '/publish/execute' },
  { key: 'analysis', name: '数据分析 Agent', icon: BarChart3, color: '#06b6d4', port: 8085, desc: '分析运营数据并生成洞察', endpoint: '/analysis/execute' },
  { key: 'optimize', name: '优化迭代 Agent', icon: RefreshCw, color: '#ec4899', port: 8086, desc: '基于数据优化策略', endpoint: '/optimize/execute' },
]

export function AgentsPage() {
  const { accountProfile } = useWorkflowStore()
  const [selected, setSelected] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const handleExecute = async (agentKey: string) => {
    setSelected(agentKey)
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const req = {
        accountProfile,
        inputs: { testMode: true },
        requireHumanReview: false,
      }
      const res = await (agentApi as any)[agentKey].execute(req)
      setResult(JSON.stringify(res, null, 2))
    } catch (err) {
      setError(err instanceof Error ? err.message : '调用失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-6 animate-fade-in">
      <div>
        <h1 className="text-xl font-bold text-gray-900">Agent 面板</h1>
        <p className="text-sm text-gray-500">直接调用单个 Agent 服务，独立测试各模块功能</p>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {agents.map((agent) => (
          <Card key={agent.key} className={selected === agent.key ? 'border-brand-300 ring-1 ring-brand-200' : ''}>
            <CardContent className="pt-6">
              <div className="flex items-start justify-between">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg" style={{ backgroundColor: agent.color + '20' }}>
                  <agent.icon className="h-5 w-5" style={{ color: agent.color }} />
                </div>
                <Badge variant="default">:{agent.port}</Badge>
              </div>
              <h3 className="mt-3 font-semibold text-gray-900">{agent.name}</h3>
              <p className="mt-1 text-sm text-gray-500">{agent.desc}</p>
              {agent.endpoint && (
                <p className="mt-2 font-mono text-xs text-gray-400">{agent.endpoint}</p>
              )}
              {agent.endpoints && (
                <div className="mt-2 space-y-0.5">
                  {agent.endpoints.map((ep, i) => (
                    <p key={i} className="font-mono text-xs text-gray-400">{ep}</p>
                  ))}
                </div>
              )}
              <Button
                size="sm"
                variant={selected === agent.key ? 'primary' : 'outline'}
                className="mt-3 w-full"
                onClick={() => handleExecute(agent.key)}
                loading={loading && selected === agent.key}
              >
                <Play className="h-3 w-3" />
                测试调用
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>

      {(result || error) && selected && (
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Bot className="h-5 w-5 text-brand-500" />
                <CardTitle>调用结果 — {agents.find(a => a.key === selected)?.name}</CardTitle>
              </div>
              <Button variant="ghost" size="sm" onClick={() => { setResult(null); setError(null); setSelected(null) }}>
                关闭
              </Button>
            </div>
          </CardHeader>
          <CardContent>
            {error && (
              <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-600">
                {error}
              </div>
            )}
            {result && (
              <pre className="max-h-96 overflow-auto rounded-lg bg-gray-900 p-4 text-xs text-gray-100 scrollbar-thin">
                {result}
              </pre>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
