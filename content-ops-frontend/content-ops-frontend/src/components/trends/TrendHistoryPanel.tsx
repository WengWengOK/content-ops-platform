import { useEffect, useState } from 'react'
import { trendHistory } from '@/api/trends'
import type { TrendHistoryPoint, TrendPlatformHeat } from '@/types'

const PLATFORM_NAMES: Record<string, string> = {
  xiaohongshu: '小红书',
  weibo: '微博',
  douyin: '抖音',
  bilibili: '哔哩哔哩',
  zhihu: '知乎',
  baidu: '百度',
  toutiao: '今日头条',
}

interface Props {
  title: string
  platform?: string
  onClose: () => void
}

function formatUptime(hours: number): string {
  if (hours < 1) return `${Math.round(hours * 60)} 分钟`
  if (hours < 24) return `${hours.toFixed(1)} 小时`
  return `${(hours / 24).toFixed(1)} 天`
}

function HeatChart({ points }: { points: TrendHistoryPoint[] }) {
  const width = 640
  const height = 150
  const pad = 24
  const valid = points.filter((p) => p.heat != null) as (TrendHistoryPoint & { heat: number })[]
  if (valid.length < 2) {
    return (
      <div className="flex h-[150px] items-center justify-center text-xs" style={{ color: '#C9CDD4' }}>
        数据点不足，暂无法绘制曲线（至少需要两轮快照）
      </div>
    )
  }
  const min = Math.min(...valid.map((p) => p.heat))
  const max = Math.max(...valid.map((p) => p.heat))
  const range = max - min || 1
  const pts = valid.map((p, i) => {
    const x = pad + (i / (valid.length - 1)) * (width - pad * 2)
    const y = pad + (1 - (p.heat - min) / range) * (height - pad * 2)
    return { x, y, p }
  })
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="w-full" style={{ maxHeight: 180 }}>
      <polyline
        points={pts.map((t) => `${t.x},${t.y}`).join(' ')}
        fill="none"
        stroke="#165DFF"
        strokeWidth="2"
      />
      {pts.map((t, i) => (
        <g key={i}>
          <circle cx={t.x} cy={t.y} r="3" fill="#165DFF" />
          <text x={t.x} y={height - 6} fontSize="9" fill="#86909C" textAnchor="middle">
            {t.p.capturedAt ? t.p.capturedAt.slice(11, 16) : ''}
          </text>
          {i === 0 && (
            <text x={t.x} y={t.y - 8} fontSize="10" fill="#86909C">
              最低 {min.toLocaleString()}
            </text>
          )}
          {i === pts.length - 1 && (
            <text x={t.x} y={t.y - 8} fontSize="10" fill="#F53F3F" textAnchor="end">
              当前 {max.toLocaleString()}
            </text>
          )}
        </g>
      ))}
    </svg>
  )
}

export function TrendHistoryPanel({ title, platform, onClose }: Props) {
  const [points, setPoints] = useState<TrendHistoryPoint[]>([])
  const [platforms, setPlatforms] = useState<TrendPlatformHeat[]>([])
  const [uptimeHours, setUptimeHours] = useState(0)
  const [error, setError] = useState('')

  useEffect(() => {
    trendHistory(title, platform, 24)
      .then((d) => {
        setPoints(d.points ?? [])
        setPlatforms(d.platforms ?? [])
        setUptimeHours(d.uptimeHours ?? 0)
      })
      .catch((e: any) => setError(e?.message || '趋势加载失败'))
  }, [title, platform])

  return (
    <div className="card mb-4 p-5">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-sm font-semibold" style={{ color: '#1D2129' }}>
            📈 {title}
          </h2>
          <p className="mt-0.5 text-xs" style={{ color: '#86909C' }}>
            已上榜 {formatUptime(uptimeHours)}
            {platform ? ` · ${PLATFORM_NAMES[platform] ?? platform} 近24h热度曲线` : ' · 近24h热度曲线'}
          </p>
        </div>
        <button
          onClick={onClose}
          className="rounded px-2 py-1 text-xs font-medium"
          style={{ background: '#F2F3F5', color: '#4E5969', border: 'none', cursor: 'pointer' }}
        >
          关闭 ✕
        </button>
      </div>

      {error ? (
        <p className="text-sm" style={{ color: '#F53F3F' }}>{error}</p>
      ) : (
        <>
          <HeatChart points={points} />

          {platforms.length > 0 && (
            <div className="mt-4">
              <p className="mb-2 text-xs font-semibold" style={{ color: '#4E5969' }}>平台对比（最近热度）</p>
              <div className="space-y-1.5">
                {platforms.map((p) => (
                  <div key={p.platform} className="flex items-center justify-between gap-3 text-xs">
                    <span className="min-w-0 truncate" style={{ color: '#4E5969' }}>
                      {PLATFORM_NAMES[p.platform] ?? p.platform}
                      {p.rank != null && <span className="ml-1" style={{ color: '#86909C' }}># {p.rank}</span>}
                    </span>
                    <span className="flex-shrink-0" style={{ color: '#1D2129' }}>
                      {p.heat != null ? p.heat.toLocaleString() : '—'}
                      {p.url && (
                        <a href={p.url} target="_blank" rel="noreferrer" className="ml-2 hover:underline" style={{ color: '#165DFF' }}>
                          原文 ↗
                        </a>
                      )}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
