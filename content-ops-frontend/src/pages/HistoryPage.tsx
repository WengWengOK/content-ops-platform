import { useState } from 'react'
import { History, Search, Inbox } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'

interface HistoryItem {
  id: string
  workflowId: string
  niche: string
  status: string
  createdAt: string
  cycleCount: number
  stages: string[]
}

export function HistoryPage() {
  const [search, setSearch] = useState('')
  const [items] = useState<HistoryItem[]>([])

  const filtered = items.filter(i =>
    i.workflowId.includes(search) || i.niche.includes(search)
  )

  return (
    <div className="space-y-6 animate-fade-in">
      <div>
        <h1 className="text-xl font-bold text-gray-900">历史记录</h1>
        <p className="text-sm text-gray-500">查看过往工作流执行记录和结果</p>
      </div>

      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="搜索工作流ID或领域..."
            className="w-full rounded-lg border border-gray-300 py-2 pl-10 pr-4 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>
        <Button variant="outline" size="sm">
          <History className="h-4 w-4" />
          刷新
        </Button>
      </div>

      {filtered.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-16">
            <Inbox className="h-12 w-12 text-gray-300" />
            <p className="mt-3 text-sm text-gray-500">暂无历史记录</p>
            <p className="text-xs text-gray-400">完成的工作流将显示在这里</p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {filtered.map((item) => (
            <Card key={item.id} className="cursor-pointer hover:border-brand-300">
              <CardContent className="pt-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-medium text-gray-900">{item.niche}</p>
                    <p className="text-xs text-gray-400">{item.workflowId}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant="default">循环 {item.cycleCount}</Badge>
                    <Badge variant={item.status === 'COMPLETED' ? 'success' : 'error'}>
                      {item.status}
                    </Badge>
                  </div>
                </div>
                <div className="mt-2 flex flex-wrap gap-1">
                  {item.stages.map((s, i) => (
                    <Badge key={i} variant="info">{s}</Badge>
                  ))}
                </div>
                <p className="mt-2 text-xs text-gray-400">
                  {new Date(item.createdAt).toLocaleString('zh-CN')}
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
