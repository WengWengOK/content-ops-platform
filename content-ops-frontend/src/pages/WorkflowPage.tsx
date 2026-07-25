import { useEffect } from 'react'
import {
  Lightbulb, PenLine, Image as ImageIcon, Send,
  BarChart3, RefreshCw, CheckCircle2, Clock, XCircle,
  Play, ChevronRight, Settings
} from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Loading } from '@/components/ui/Loading'
import { useWorkflowStore } from '@/store/workflowStore'
import { STAGE_META, STAGE_ORDER, STATUS_META, PLATFORM_OPTIONS, TONE_OPTIONS } from '@/utils/constants'
import type { StageCode } from '@/types'
import { useState } from 'react'

const stageIcons: Record<string, typeof Lightbulb> = {
  TOPIC_PLANNING: Lightbulb,
  CONTENT_CREATION: PenLine,
  IMAGE_DESIGN: ImageIcon,
  PUBLISHING: Send,
  DATA_ANALYSIS: BarChart3,
  OPTIMIZATION: RefreshCw,
}

export function WorkflowPage() {
  const {
    taskContext, loading, error, accountProfile, requireHumanReview,
    setAccountProfile, setRequireHumanReview, startWorkflow, approveStage,
  } = useWorkflowStore()
  const [showConfig, setShowConfig] = useState(true)

  if (!taskContext && !loading) {
    return (
      <div className="space-y-6 animate-fade-in">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-gray-900">工作流</h1>
            <p className="text-sm text-gray-500">配置账号信息并启动内容运营流水线</p>
          </div>
        </div>

        {showConfig && (
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Settings className="h-5 w-5 text-brand-500" />
                  <CardTitle>账号配置</CardTitle>
                </div>
                <Button variant="ghost" size="sm" onClick={() => setShowConfig(false)}>收起</Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">账号名称</label>
                  <input
                    type="text"
                    value={accountProfile.accountName}
                    onChange={(e) => setAccountProfile({ accountName: e.target.value })}
                    placeholder="输入账号名称"
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">内容领域</label>
                  <input
                    type="text"
                    value={accountProfile.niche}
                    onChange={(e) => setAccountProfile({ niche: e.target.value })}
                    placeholder="如：个人成长、科技、美食"
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">目标受众</label>
                  <input
                    type="text"
                    value={accountProfile.targetAudience}
                    onChange={(e) => setAccountProfile({ targetAudience: e.target.value })}
                    placeholder="如：25-35岁职场人士"
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">内容调性</label>
                  <select
                    value={accountProfile.tone}
                    onChange={(e) => setAccountProfile({ tone: e.target.value })}
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                  >
                    {TONE_OPTIONS.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">发布平台</label>
                <div className="flex flex-wrap gap-2">
                  {PLATFORM_OPTIONS.map(p => (
                    <button
                      key={p.value}
                      onClick={() => {
                        const platforms = accountProfile.platforms.includes(p.value)
                          ? accountProfile.platforms.filter(x => x !== p.value)
                          : [...accountProfile.platforms, p.value]
                        setAccountProfile({ platforms })
                      }}
                      className={`rounded-lg border px-3 py-1.5 text-sm transition-colors ${
                        accountProfile.platforms.includes(p.value)
                          ? 'border-brand-500 bg-brand-50 text-brand-700'
                          : 'border-gray-300 text-gray-600 hover:border-gray-400'
                      }`}
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">个人经验/背景</label>
                <textarea
                  value={accountProfile.personalExperience}
                  onChange={(e) => setAccountProfile({ personalExperience: e.target.value })}
                  placeholder="描述你的个人经历、专业背景等"
                  rows={3}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                />
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="humanReview"
                  checked={requireHumanReview}
                  onChange={(e) => setRequireHumanReview(e.target.checked)}
                  className="h-4 w-4 rounded border-gray-300 text-brand-600 focus:ring-brand-500"
                />
                <label htmlFor="humanReview" className="text-sm text-gray-700">
                  启用人工审核（每个阶段完成后需确认）
                </label>
              </div>
            </CardContent>
          </Card>
        )}

        {error && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-600">
            {error}
          </div>
        )}

        <div className="flex justify-center">
          <Button size="lg" onClick={startWorkflow} loading={loading}>
            <Play className="h-5 w-5" />
            启动工作流
          </Button>
        </div>

        {!showConfig && (
          <div className="text-center">
            <Button variant="outline" size="sm" onClick={() => setShowConfig(true)}>
              <Settings className="h-4 w-4" /> 展开配置
            </Button>
          </div>
        )}
      </div>
    )
  }

  if (loading && !taskContext) {
    return <Loading size="lg" text="正在启动工作流..." className="py-20" />
  }

  const currentStageIndex = STAGE_ORDER.indexOf(taskContext?.currentStage as StageCode)
  const status = taskContext?.status ? STATUS_META[taskContext.status] : null

  return (
    <div className="space-y-6 animate-fade-in">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">工作流执行</h1>
          <p className="text-sm text-gray-500">
            ID: {taskContext?.workflowId} · 循环 {taskContext?.cycleCount || 1}/{taskContext?.maxCycles || 3}
          </p>
        </div>
        {status && (
          <Badge variant={
            taskContext?.status === 'COMPLETED' ? 'success' :
            taskContext?.status === 'FAILED' ? 'error' :
            taskContext?.status === 'WAITING_FOR_REVIEW' ? 'warning' :
            taskContext?.status === 'PROCESSING' ? 'info' : 'default'
          }>
            {status.label}
          </Badge>
        )}
      </div>

      {/* Pipeline Visualizer */}
      <Card>
        <CardContent className="pt-6">
          <div className="flex items-center gap-1 overflow-x-auto pb-2 scrollbar-thin">
            {STAGE_ORDER.map((code, i) => {
              const meta = STAGE_META[code]
              const Icon = stageIcons[code] || Lightbulb
              const isCurrent = i === currentStageIndex
              const isPast = i < currentStageIndex
              const isFuture = i > currentStageIndex
              return (
                <div key={code} className="flex flex-shrink-0 items-center">
                  <div className={`flex flex-col items-center gap-1 rounded-lg border px-3 py-2 transition-all ${
                    isCurrent ? 'border-brand-500 bg-brand-50 shadow-sm' :
                    isPast ? 'border-green-200 bg-green-50/50' :
                    'border-gray-200 opacity-50'
                  }`} style={isCurrent ? { borderColor: meta.color } : {}}>
                    <div className="flex h-7 w-7 items-center justify-center rounded-full"
                      style={{ backgroundColor: isPast ? '#10b981' : meta.color }}>
                      {isPast ? <CheckCircle2 className="h-4 w-4 text-white" /> :
                       <Icon className="h-4 w-4 text-white" />}
                    </div>
                    <span className="text-xs font-medium text-gray-700">{meta.name}</span>
                    {isCurrent && taskContext?.status === 'PROCESSING' && (
                      <Clock className="h-3 w-3 animate-pulse text-brand-500" />
                    )}
                  </div>
                  {i < STAGE_ORDER.length - 1 && (
                    <ChevronRight className={`mx-0.5 h-4 w-4 ${isPast ? 'text-green-400' : 'text-gray-300'}`} />
                  )}
                </div>
              )
            })}
          </div>
        </CardContent>
      </Card>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-600">
          {error}
        </div>
      )}

      {taskContext?.status === 'WAITING_FOR_REVIEW' && (
        <Card className="border-amber-200 bg-amber-50/50">
          <CardContent className="pt-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Clock className="h-5 w-5 text-amber-500" />
                <div>
                  <p className="font-semibold text-amber-900">等待人工审核</p>
                  <p className="text-sm text-amber-700">
                    当前阶段: {STAGE_META[taskContext.currentStage as StageCode]?.name || taskContext.currentStage}
                    {taskContext.currentSubStage && ` → ${taskContext.currentSubStage}`}
                  </p>
                </div>
              </div>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" onClick={() => approveStage({ action: 'reject' })}>
                  驳回
                </Button>
                <Button size="sm" onClick={() => approveStage()} loading={loading}>
                  <CheckCircle2 className="h-4 w-4" /> 确认通过
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {taskContext?.status === 'FAILED' && (
        <Card className="border-red-200 bg-red-50/50">
          <CardContent className="pt-6">
            <div className="flex items-center gap-2">
              <XCircle className="h-5 w-5 text-red-500" />
              <div>
                <p className="font-semibold text-red-900">工作流执行失败</p>
                <p className="text-sm text-red-700">{taskContext.errorMessage}</p>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {taskContext?.accumulatedArtifacts && Object.keys(taskContext.accumulatedArtifacts).length > 0 && (
        <Card>
          <CardHeader><CardTitle>已生成产物</CardTitle></CardHeader>
          <CardContent>
            <div className="space-y-2">
              {Object.entries(taskContext.accumulatedArtifacts).map(([key, value]) => (
                <div key={key} className="rounded-lg border border-gray-100 p-3">
                  <p className="text-xs font-semibold text-gray-500">{key}</p>
                  <pre className="mt-1 overflow-x-auto text-xs text-gray-600 max-h-40 scrollbar-thin">
                    {typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
                  </pre>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
