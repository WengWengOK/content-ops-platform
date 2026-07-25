import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Workflow, MessageSquareText, Bot, Sparkles,
  ArrowRight, Activity, Zap, CheckCircle2
} from 'lucide-react'
import { Card, CardContent } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { useWorkflowStore } from '@/store/workflowStore'
import { STAGE_META, STAGE_ORDER } from '@/utils/constants'

export function Dashboard() {
  const navigate = useNavigate()
  const { stages, fetchStages, accountProfile } = useWorkflowStore()

  useEffect(() => {
    fetchStages()
  }, [fetchStages])

  const features = [
    {
      title: '一键启动工作流',
      desc: '配置账号信息，从选题到优化全流程自动化',
      icon: Workflow,
      action: () => navigate('/workflow'),
      color: 'bg-blue-500',
    },
    {
      title: '对话式选题讨论',
      desc: '通过自然语言对话，与AI共同打磨内容方向',
      icon: MessageSquareText,
      action: () => navigate('/discussion'),
      color: 'bg-amber-500',
    },
    {
      title: 'Agent 独立调用',
      desc: '直接调用单个Agent服务，灵活组合使用',
      icon: Bot,
      action: () => navigate('/agents'),
      color: 'bg-purple-500',
    },
  ]

  return (
    <div className="space-y-6 animate-fade-in">
      <div className="rounded-2xl bg-gradient-to-r from-brand-600 to-brand-800 p-8 text-white">
        <div className="flex items-center gap-2">
          <Sparkles className="h-6 w-6" />
          <h1 className="text-2xl font-bold">Content Ops Agent Platform</h1>
        </div>
        <p className="mt-2 text-brand-100">AI 驱动的多 Agent 内容运营协作平台 — 从选题到优化，全链路自动化</p>
        <div className="mt-4 flex gap-3">
          <Button variant="secondary" onClick={() => navigate('/workflow')}>
            启动工作流 <ArrowRight className="h-4 w-4" />
          </Button>
          <Button variant="ghost" className="text-white hover:bg-white/10" onClick={() => navigate('/discussion')}>
            讨论选题
          </Button>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        {features.map((f, i) => (
          <Card key={i} className="cursor-pointer transition-transform hover:scale-[1.02]" >
            <CardContent className="pt-6">
              <div className={`mb-3 flex h-10 w-10 items-center justify-center rounded-lg ${f.color}`}>
                <f.icon className="h-5 w-5 text-white" />
              </div>
              <h3 className="font-semibold text-gray-900">{f.title}</h3>
              <p className="mt-1 text-sm text-gray-500">{f.desc}</p>
              <button onClick={f.action} className="mt-3 flex items-center gap-1 text-sm text-brand-600 hover:text-brand-700">
                进入 <ArrowRight className="h-3 w-3" />
              </button>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardContent className="pt-6">
          <div className="mb-4 flex items-center gap-2">
            <Activity className="h-5 w-5 text-brand-500" />
            <h2 className="text-lg font-semibold">流水线阶段</h2>
          </div>
          <div className="grid gap-3 md:grid-cols-6">
            {STAGE_ORDER.map((code, i) => {
              const meta = STAGE_META[code]
              return (
                <div key={code} className="relative">
                  <div className="rounded-lg border border-gray-200 p-3 text-center">
                    <div className="mx-auto mb-2 flex h-8 w-8 items-center justify-center rounded-full text-xs font-bold text-white"
                      style={{ backgroundColor: meta.color }}>
                      {i + 1}
                    </div>
                    <p className="text-sm font-medium text-gray-900">{meta.name}</p>
                    <p className="mt-0.5 text-xs text-gray-400">{meta.description}</p>
                  </div>
                  {i < STAGE_ORDER.length - 1 && (
                    <ArrowRight className="absolute -right-2 top-1/2 hidden h-4 w-4 -translate-y-1/2 text-gray-300 md:block" />
                  )}
                </div>
              )
            })}
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardContent className="pt-6">
            <div className="mb-3 flex items-center gap-2">
              <Zap className="h-5 w-5 text-amber-500" />
              <h3 className="font-semibold">当前账号配置</h3>
            </div>
            <dl className="space-y-1.5 text-sm">
              <div className="flex justify-between">
                <dt className="text-gray-500">领域</dt>
                <dd className="font-medium text-gray-900">{accountProfile.niche}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">目标受众</dt>
                <dd className="font-medium text-gray-900">{accountProfile.targetAudience}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">调性</dt>
                <dd className="font-medium text-gray-900">{accountProfile.tone}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">平台</dt>
                <dd className="font-medium text-gray-900">{accountProfile.platforms.join('、')}</dd>
              </div>
            </dl>
            <Button variant="outline" size="sm" className="mt-3" onClick={() => navigate('/workflow')}>
              修改配置
            </Button>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-6">
            <div className="mb-3 flex items-center gap-2">
              <CheckCircle2 className="h-5 w-5 text-green-500" />
              <h3 className="font-semibold">系统状态</h3>
            </div>
            <div className="space-y-2 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-gray-500">编排器 (Orchestrator)</span>
                <span className="flex items-center gap-1 text-green-600">
                  <span className="h-2 w-2 rounded-full bg-green-500"></span> 就绪
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-gray-500">Agent 服务</span>
                <span className="flex items-center gap-1 text-green-600">
                  <span className="h-2 w-2 rounded-full bg-green-500"></span> 6 个已注册
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-gray-500">引擎模式</span>
                <span className="text-gray-700">Legacy / LangGraph</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-gray-500">人工审核</span>
                <span className="text-gray-700">已启用</span>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
