import { PenLine, FileText, ListChecks } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { MarkdownRenderer } from '@/components/ui/MarkdownRenderer'
import type { OutlineResult, ContentDraftResult } from '@/types'

interface ContentResultPanelProps {
  outline?: OutlineResult | null
  draft?: ContentDraftResult | null
  rawContent?: string
}

export function ContentResultPanel({ outline, draft, rawContent }: ContentResultPanelProps) {
  return (
    <div className="space-y-4">
      {outline && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <ListChecks className="h-5 w-5 text-blue-500" />
              <CardTitle>内容大纲</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <div className="mb-3 flex items-center gap-2">
              <h4 className="text-lg font-bold text-gray-900">{outline.title}</h4>
              <Badge variant="info">{outline.estimatedWordCount} 字</Badge>
              {outline.angle && <Badge variant="purple">{outline.angle}</Badge>}
            </div>
            <div className="space-y-3">
              <div>
                <h5 className="mb-1 text-sm font-semibold text-gray-700">引言</h5>
                <p className="text-sm text-gray-600">{outline.outline?.introduction}</p>
              </div>
              {outline.outline?.sections?.map((section, i) => (
                <div key={i} className="rounded-lg border border-gray-100 p-3">
                  <h5 className="font-semibold text-gray-900">{i + 1}. {section.heading}</h5>
                  {section.keyPoints && (
                    <ul className="mt-1.5 space-y-1">
                      {section.keyPoints.map((point, j) => (
                        <li key={j} className="text-sm text-gray-600">• {point}</li>
                      ))}
                    </ul>
                  )}
                  {section.example && (
                    <p className="mt-1.5 text-xs text-gray-400 italic">示例: {section.example}</p>
                  )}
                </div>
              ))}
              <div>
                <h5 className="mb-1 text-sm font-semibold text-gray-700">结语</h5>
                <p className="text-sm text-gray-600">{outline.outline?.conclusion}</p>
              </div>
            </div>
            {outline.writingNotes?.length > 0 && (
              <div className="mt-3 rounded-lg bg-blue-50 p-3">
                <p className="mb-1 text-xs font-semibold text-blue-700">写作笔记</p>
                <ul className="space-y-0.5">
                  {outline.writingNotes.map((note, i) => (
                    <li key={i} className="text-xs text-blue-600">• {note}</li>
                  ))}
                </ul>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {(draft || rawContent) && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <PenLine className="h-5 w-5 text-blue-500" />
              <CardTitle>内容初稿</CardTitle>
            </div>
            {draft && (
              <div className="flex items-center gap-2">
                <Badge variant="info">{draft.wordCount} 字</Badge>
                {draft.tags?.map((tag, i) => <Badge key={i} variant="default">{tag}</Badge>)}
              </div>
            )}
          </CardHeader>
          <CardContent>
            {draft?.titleVariations && draft.titleVariations.length > 0 && (
              <div className="mb-3">
                <p className="mb-1 text-xs font-semibold text-gray-500">标题变体</p>
                <div className="space-y-1">
                  {draft!.titleVariations.map((title, i) => (
                    <p key={i} className="text-sm text-gray-700">{i + 1}. {title}</p>
                  ))}
                </div>
              </div>
            )}
            {draft?.summary && (
              <div className="mb-3 rounded-lg bg-gray-50 p-3">
                <p className="text-sm text-gray-600">{draft.summary}</p>
              </div>
            )}
            <MarkdownRenderer content={draft?.draftContent || rawContent || ''} />
          </CardContent>
        </Card>
      )}
    </div>
  )
}
