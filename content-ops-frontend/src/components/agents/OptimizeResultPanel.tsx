import { RefreshCw, Heart, BookOpen, Target } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import type { OptimizationResult, StrategyAdjustment } from '@/types'

interface OptimizeResultPanelProps {
  data: OptimizationResult
}

const dimensionLabels: Record<string, string> = {
  content_type: '内容类型',
  posting_time: '发布时间',
  platform_focus: '平台重心',
  tone: '内容风格',
}

function getHealthColor(score: number) {
  if (score >= 80) return { color: 'text-green-600', bg: 'bg-green-500', label: '优秀' }
  if (score >= 60) return { color: 'text-amber-600', bg: 'bg-amber-500', label: '良好' }
  if (score >= 40) return { color: 'text-orange-600', bg: 'bg-orange-500', label: '待改善' }
  return { color: 'text-red-600', bg: 'bg-red-500', label: '预警' }
}

export function OptimizeResultPanel({ data }: OptimizeResultPanelProps) {
  const health = getHealthColor(data.healthScore)

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Heart className="h-5 w-5 text-pink-500" />
            <CardTitle>运营健康评分</CardTitle>
          </div>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-6">
            <div className="relative flex h-24 w-24 items-center justify-center">
              <svg className="h-24 w-24 -rotate-90" viewBox="0 0 100 100">
                <circle cx="50" cy="50" r="42" fill="none" stroke="#e5e7eb" strokeWidth="8" />
                <circle
                  cx="50" cy="50" r="42" fill="none" stroke="currentColor"
                  strokeWidth="8" strokeLinecap="round"
                  className={health.color}
                  strokeDasharray={`${(data.healthScore / 100) * 264} 264`}
                />
              </svg>
              <div className="absolute flex flex-col items-center">
                <span className={`text-2xl font-bold ${health.color}`}>{data.healthScore.toFixed(0)}</span>
                <span className="text-xs text-gray-400">/100</span>
              </div>
            </div>
            <div>
              <Badge variant={data.healthScore >= 80 ? 'success' : data.healthScore >= 60 ? 'warning' : 'error'}>
                {health.label}
              </Badge>
              {data.cycleSummary && (
                <p className="mt-2 max-w-md text-sm text-gray-600">{data.cycleSummary}</p>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {data.strategyAdjustments?.length > 0 && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <RefreshCw className="h-5 w-5 text-pink-500" />
              <CardTitle>策略调整建议 ({data.strategyAdjustments.length})</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {data.strategyAdjustments.map((adj: StrategyAdjustment, i) => (
                <div key={i} className="rounded-lg border border-gray-200 p-4">
                  <div className="flex items-center gap-2">
                    <Badge variant="purple">{dimensionLabels[adj.dimension] || adj.dimension}</Badge>
                    <div className="flex items-center gap-2 text-sm">
                      <span className="text-gray-500 line-through">{adj.currentValue}</span>
                      <span className="text-gray-400">→</span>
                      <span className="font-semibold text-brand-700">{adj.recommendedValue}</span>
                    </div>
                    {adj.expectedImpact > 0 && (
                      <Badge variant="success">预期影响 +{(adj.expectedImpact * 100).toFixed(0)}%</Badge>
                    )}
                  </div>
                  {adj.rationale && (
                    <p className="mt-2 text-sm text-gray-600">{adj.rationale}</p>
                  )}
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {data.recommendedTopics?.length > 0 && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Target className="h-5 w-5 text-pink-500" />
              <CardTitle>下周期推荐选题</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {data.recommendedTopics.map((topic, i) => (
                <div key={i} className="flex items-start gap-2 text-sm text-gray-700">
                  <span className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-pink-100 text-xs font-bold text-pink-600">
                    {i + 1}
                  </span>
                  {topic}
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {data.learnings?.length > 0 && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <BookOpen className="h-5 w-5 text-pink-500" />
              <CardTitle>经验总结</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <ul className="space-y-2">
              {data.learnings.map((learning, i) => (
                <li key={i} className="flex items-start gap-2 text-sm text-gray-700">
                  <Badge variant="default">{i + 1}</Badge>
                  {learning}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
