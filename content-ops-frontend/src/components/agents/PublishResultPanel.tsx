import { Send, CheckCircle2, XCircle, AlertCircle, ExternalLink } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import type { PublishResult } from '@/types'

interface PublishResultPanelProps {
  data: PublishResult
}

const statusConfig = {
  PUBLISHED: { icon: CheckCircle2, color: 'text-green-600', bg: 'bg-green-100', label: '已发布' },
  DRAFT: { icon: AlertCircle, color: 'text-amber-600', bg: 'bg-amber-100', label: '草稿' },
  FAILED: { icon: XCircle, color: 'text-red-600', bg: 'bg-red-100', label: '失败' },
}

const overallStatusConfig = {
  SUCCESS: { variant: 'success' as const, label: '全部成功' },
  PARTIAL: { variant: 'warning' as const, label: '部分成功' },
  FAILED: { variant: 'error' as const, label: '全部失败' },
}

export function PublishResultPanel({ data }: PublishResultPanelProps) {
  const overall = data.status ? overallStatusConfig[data.status] : null
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Send className="h-5 w-5 text-green-500" />
            <CardTitle>发布结果</CardTitle>
          </div>
          {overall && <Badge variant={overall.variant}>{overall.label}</Badge>}
        </div>
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          {data.publications?.map((pub, i) => {
            const cfg = pub.status ? statusConfig[pub.status] : statusConfig.DRAFT
            return (
              <div key={i} className="flex items-start gap-3 rounded-lg border border-gray-200 p-3">
                <div className={`flex h-8 w-8 items-center justify-center rounded-full ${cfg.bg}`}>
                  <cfg.icon className={`h-4 w-4 ${cfg.color}`} />
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-gray-900">{pub.platform}</span>
                    <Badge variant="default">{cfg.label}</Badge>
                    {pub.publishedAt && (
                      <span className="text-xs text-gray-400">
                        {new Date(pub.publishedAt).toLocaleString('zh-CN')}
                      </span>
                    )}
                  </div>
                  {pub.failureReason && (
                    <p className="mt-1 text-xs text-red-500">{pub.failureReason}</p>
                  )}
                  {pub.articleUrl && (
                    <a
                      href={pub.articleUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="mt-1 inline-flex items-center gap-1 text-xs text-brand-600 hover:text-brand-700"
                    >
                      查看文章 <ExternalLink className="h-3 w-3" />
                    </a>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      </CardContent>
    </Card>
  )
}
