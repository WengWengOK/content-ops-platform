import { useCallback, useEffect, useState } from 'react'
import { Layout } from '@/components/layout/Layout'
import { llmStats, llmTraces } from '@/api/observability'
import type { LlmStats, LlmTrace } from '@/types'

const HOUR_OPTIONS = [
  { value: 1, label: '近1小时' },
  { value: 24, label: '近24小时' },
  { value: 168, label: '近7天' },
]

function StatCard({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="card p-4">
      <p className="text-xs" style={{ color: '#86909C' }}>{label}</p>
      <p className="mt-1 text-lg font-bold" style={{ color: color ?? '#1D2129' }}>{value}</p>
    </div>
  )
}

export function ObservabilityPage() {
  const [hours, setHours] = useState(24)
  const [stats, setStats] = useState<LlmStats | null>(null)
  const [traces, setTraces] = useState<LlmTrace[]>([])
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setError('')
    try {
      const [s, t] = await Promise.all([llmStats(hours), llmTraces(undefined, undefined, undefined, 50)])
      setStats(s)
      setTraces(t)
    } catch (e: any) {
      setError(e?.message || '加载失败')
    }
  }, [hours])

  useEffect(() => {
    void load()
  }, [load])

  const maxTokens = Math.max(
    1,
    ...(stats?.timeseries ?? []).map((p) => p.tokens_in + p.tokens_out)
  )

  return (
    <Layout activeNav="observability" breadcrumbs={[{ label: 'LLM 观测' }]}>
      <div className="mx-auto max-w-6xl p-6">
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold" style={{ color: '#1D2129' }}>LLM 观测</h1>
            <p className="mt-1 text-sm" style={{ color: '#86909C' }}>
              每次模型调用的 token / 延迟 / 成本与错误追踪
            </p>
          </div>
          <div className="flex gap-2">
            {HOUR_OPTIONS.map((o) => (
              <button
                key={o.value}
                onClick={() => setHours(o.value)}
                className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
                style={{
                  background: hours === o.value ? '#165DFF' : '#F2F3F5',
                  color: hours === o.value ? '#fff' : '#4E5969',
                  border: 'none',
                  cursor: 'pointer',
                }}
              >
                {o.label}
              </button>
            ))}
          </div>
        </div>

        {error && (
          <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: '#F53F3F', color: '#F53F3F', background: '#FFF0F0' }}>
            {error}
          </div>
        )}

        {stats && (
          <>
            <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
              <StatCard label="调用次数" value={stats.calls.toLocaleString()} />
              <StatCard label="输入 Token" value={stats.tokensIn.toLocaleString()} color="#165DFF" />
              <StatCard label="输出 Token" value={stats.tokensOut.toLocaleString()} color="#0FC6C2" />
              <StatCard label="估算成本" value={`$${stats.estimatedCostUsd.toFixed(4)}`} color="#FF7D00" />
              <StatCard label="平均延迟" value={`${stats.avgLatencyMs} ms`} />
              <StatCard
                label="错误率"
                value={`${stats.errorRate}%`}
                color={stats.errorRate > 5 ? '#F53F3F' : '#00B42A'}
              />
            </div>

            <div className="mb-6 grid gap-4 lg:grid-cols-2">
              {/* 阶段/Agent 排行 */}
              <div className="card p-5">
                <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>按阶段 / Agent</h2>
                {stats.byStageAgent.length === 0 ? (
                  <p className="text-xs" style={{ color: '#C9CDD4' }}>暂无数据</p>
                ) : (
                  <div className="space-y-2">
                    {stats.byStageAgent.map((r) => (
                      <div key={`${r.stage}-${r.agent}`} className="flex items-center justify-between text-xs">
                        <span style={{ color: '#4E5969' }}>
                          {r.stage}
                          {r.agent && r.agent !== r.stage && <span className="ml-1" style={{ color: '#86909C' }}>/ {r.agent}</span>}
                          <span className="ml-2" style={{ color: '#C9CDD4' }}>{r.calls} 次</span>
                        </span>
                        <span style={{ color: '#1D2129' }}>
                          {r.tokens_in.toLocaleString()} → {r.tokens_out.toLocaleString()}
                          <span className="ml-2" style={{ color: '#86909C' }}>{Math.round(r.avg_latency_ms)}ms</span>
                          {r.errors > 0 && <span className="ml-2" style={{ color: '#F53F3F' }}>{r.errors} 错</span>}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 小时级时序 */}
              <div className="card p-5">
                <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>Token 时序（小时）</h2>
                {stats.timeseries.length === 0 ? (
                  <p className="text-xs" style={{ color: '#C9CDD4' }}>暂无数据</p>
                ) : (
                  <div className="flex h-28 items-end gap-1">
                    {stats.timeseries.map((p, i) => {
                      const total = p.tokens_in + p.tokens_out
                      const h = Math.max(4, Math.round((total / maxTokens) * 100))
                      return (
                        <div
                          key={i}
                          className="flex-1 rounded-t"
                          style={{
                            height: `${h}%`,
                            background: '#165DFF',
                            opacity: 0.45 + (total / maxTokens) * 0.55,
                          }}
                          title={`${p.bucket} · ${total.toLocaleString()} tokens · ${p.calls} 次`}
                        />
                      )
                    })}
                  </div>
                )}
              </div>
            </div>

            {/* 最近 trace */}
            <div className="card p-5">
              <h2 className="mb-3 text-sm font-semibold" style={{ color: '#1D2129' }}>最近调用</h2>
              {traces.length === 0 ? (
                <p className="text-xs" style={{ color: '#C9CDD4' }}>暂无调用记录</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr style={{ color: '#86909C' }}>
                        <th className="pb-2 text-left font-medium">时间</th>
                        <th className="pb-2 text-left font-medium">阶段</th>
                        <th className="pb-2 text-left font-medium">模型</th>
                        <th className="pb-2 text-left font-medium">Trace ID</th>
                        <th className="pb-2 text-right font-medium">输入</th>
                        <th className="pb-2 text-right font-medium">输出</th>
                        <th className="pb-2 text-right font-medium">延迟</th>
                        <th className="pb-2 text-right font-medium">状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {traces.map((t) => (
                        <tr key={t.traceId} style={{ borderTop: '1px solid #F2F3F5' }}>
                          <td className="py-2 pr-3" style={{ color: '#86909C' }}>
                            {t.createdAt ? t.createdAt.slice(5, 19) : ''}
                          </td>
                          <td className="py-2 pr-3" style={{ color: '#4E5969' }}>{t.stage ?? '—'}</td>
                          <td className="py-2 pr-3" style={{ color: '#4E5969' }}>{t.model}</td>
                          <td className="py-2 pr-3" style={{ color: '#86909C', fontFamily: 'monospace', fontSize: 11 }}>
                            {t.otelTraceId ? t.otelTraceId.slice(0, 16) + '…' : '—'}
                          </td>
                          <td className="py-2 pr-3 text-right" style={{ color: '#1D2129' }}>{t.tokensIn?.toLocaleString() ?? '—'}</td>
                          <td className="py-2 pr-3 text-right" style={{ color: '#1D2129' }}>{t.tokensOut?.toLocaleString() ?? '—'}</td>
                          <td className="py-2 pr-3 text-right" style={{ color: '#86909C' }}>{t.latencyMs ?? '—'} ms</td>
                          <td className="py-2 text-right">
                            <span
                              className="rounded px-1.5 py-0.5"
                              style={{
                                background: t.status === 'success' ? '#E8FFEA' : '#FFF0F0',
                                color: t.status === 'success' ? '#00B42A' : '#F53F3F',
                              }}
                            >
                              {t.status}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </Layout>
  )
}
