import { Lightbulb, TrendingUp, Target } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { MarkdownRenderer } from '@/components/ui/MarkdownRenderer'
import type { TopicPlanResult } from '@/types'

interface TopicResultPanelProps {
  data: TopicPlanResult
}

export function TopicResultPanel({ data }: TopicResultPanelProps) {
  if (!data) return null
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Lightbulb className="h-5 w-5 text-amber-500" />
            <CardTitle>选题候选 ({data.topics?.length || 0})</CardTitle>
          </div>
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {data.topics?.map((topic, i) => (
              <div key={i} className="rounded-lg border border-gray-200 p-4 hover:border-brand-300 hover:bg-brand-50/30 transition-colors">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-bold text-brand-600">#{i + 1}</span>
                      <h4 className="font-semibold text-gray-900">{topic.title}</h4>
                    </div>
                    <p className="mt-1 text-sm text-gray-600">{topic.angle}</p>
                    <p className="mt-1 text-xs text-gray-500">{topic.rationale}</p>
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {topic.keywords?.map((kw, j) => (
                        <Badge key={j} variant="info">{kw}</Badge>
                      ))}
                    </div>
                    {topic.platformAdaptations && Object.keys(topic.platformAdaptations).length > 0 && (
                      <div className="mt-2 space-y-1">
                        {Object.entries(topic.platformAdaptations).map(([platform, adaptation]) => (
                          <div key={platform} className="text-xs">
                            <span className="font-medium text-gray-700">{platform}:</span>{' '}
                            <span className="text-gray-500">{adaptation}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                  <div className="text-right">
                    <div className="flex items-center gap-1 text-amber-600">
                      <TrendingUp className="h-4 w-4" />
                      <span className="text-lg font-bold">{(topic.estimatedEngagement * 100).toFixed(1)}%</span>
                    </div>
                    <span className="text-xs text-gray-400">预期互动率</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {data.trendingKeywords?.length > 0 && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Target className="h-5 w-5 text-brand-500" />
              <CardTitle>热门关键词</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {data.trendingKeywords.map((kw, i) => (
                <Badge key={i} variant="purple">{kw}</Badge>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {data.competitiveAnalysis && (
        <Card>
          <CardHeader><CardTitle>竞品分析</CardTitle></CardHeader>
          <CardContent><MarkdownRenderer content={data.competitiveAnalysis} /></CardContent>
        </Card>
      )}

      {data.recommendedDirection && (
        <Card className="border-brand-200 bg-brand-50/50">
          <CardHeader><CardTitle>推荐方向</CardTitle></CardHeader>
          <CardContent><MarkdownRenderer content={data.recommendedDirection} /></CardContent>
        </Card>
      )}
    </div>
  )
}
