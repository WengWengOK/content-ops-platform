import { Image as ImageIcon, Palette, Layers } from 'lucide-react'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import type { StyleDirectionResult, ImageDesignResult } from '@/types'

interface ImageResultPanelProps {
  styles?: StyleDirectionResult | null
  images?: ImageDesignResult | null
}

export function ImageResultPanel({ styles, images }: ImageResultPanelProps) {
  return (
    <div className="space-y-4">
      {styles && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Palette className="h-5 w-5 text-purple-500" />
              <CardTitle>风格方向 ({styles.directions?.length || 0})</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            {styles.toneAnalysis && (
              <p className="mb-3 text-sm text-gray-600">{styles.toneAnalysis}</p>
            )}
            <div className="grid gap-3 md:grid-cols-3">
              {styles.directions?.map((dir, i) => (
                <div key={i} className="rounded-lg border border-gray-200 p-4 hover:border-purple-300 transition-colors">
                  <div className="flex items-center justify-between">
                    <h4 className="font-semibold text-gray-900">{dir.name}</h4>
                    <Badge variant={dir.recommendationScore >= 4 ? 'success' : 'default'}>
                      ★ {dir.recommendationScore}/5
                    </Badge>
                  </div>
                  <p className="mt-1 text-sm text-gray-600">{dir.description}</p>
                  {dir.colorPalette && (
                    <p className="mt-2 text-xs text-gray-500">色彩: {dir.colorPalette}</p>
                  )}
                  {dir.suggestedPositions?.length > 0 && (
                    <div className="mt-2">
                      <p className="text-xs font-medium text-gray-500">建议位置</p>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {dir.suggestedPositions.map((pos, j) => (
                          <Badge key={j} variant="purple">{pos}</Badge>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
            {styles.visualKeywords?.length > 0 && (
              <div className="mt-3">
                <p className="mb-1 text-xs font-semibold text-gray-500">视觉关键词</p>
                <div className="flex flex-wrap gap-1.5">
                  {styles.visualKeywords.map((kw, i) => (
                    <Badge key={i} variant="info">{kw}</Badge>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {images && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <ImageIcon className="h-5 w-5 text-purple-500" />
              <CardTitle>生成图片 ({images.images?.length || 0})</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {images.images?.map((img, i) => (
                <div key={i} className="overflow-hidden rounded-lg border border-gray-200">
                  {img.imageUrl ? (
                    <img src={img.imageUrl} alt={img.prompt} className="h-48 w-full object-cover" />
                  ) : (
                    <div className="flex h-48 items-center justify-center bg-gray-100">
                      <ImageIcon className="h-8 w-8 text-gray-300" />
                    </div>
                  )}
                  <div className="p-3">
                    <div className="flex items-center gap-1.5">
                      <Badge variant="purple">{img.style}</Badge>
                      {img.colorTone && <Badge variant="default">{img.colorTone}</Badge>}
                    </div>
                    <p className="mt-1.5 text-xs text-gray-500 line-clamp-2">{img.prompt}</p>
                    {img.position && <p className="mt-1 text-xs text-gray-400">位置: {img.position}</p>}
                  </div>
                </div>
              ))}
            </div>
            {images.covers?.length > 0 && (
              <div className="mt-4">
                <div className="mb-2 flex items-center gap-2">
                  <Layers className="h-4 w-4 text-gray-500" />
                  <h4 className="text-sm font-semibold text-gray-700">平台封面</h4>
                </div>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  {images.covers.map((cover, i) => (
                    <div key={i} className="overflow-hidden rounded-lg border border-gray-200">
                      {cover.imageUrl ? (
                        <img src={cover.imageUrl} alt={cover.platform} className="h-32 w-full object-cover" />
                      ) : (
                        <div className="flex h-32 items-center justify-center bg-gray-100">
                          <ImageIcon className="h-6 w-6 text-gray-300" />
                        </div>
                      )}
                      <div className="p-2">
                        <p className="text-xs font-medium text-gray-700">{cover.platform}</p>
                        <p className="text-xs text-gray-400">{cover.width}×{cover.height} {cover.format}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
