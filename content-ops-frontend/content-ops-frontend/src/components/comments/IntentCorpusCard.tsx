import { useCallback, useEffect, useState } from 'react'
import {
  addIntentCorpusEntry,
  deleteIntentCorpusEntry,
  getIntentCorpus,
  previewIntentMatch,
  rebuildIntentCorpus,
} from '@/api/comments'
import type { IntentCorpusEntry, IntentCorpusStats, IntentMatchPreview } from '@/types'

const INTENT_OPTIONS = ['咨询', '求教程', '售后', '吐槽', '表扬', '推广', '潜在客户', '反馈', '无关']

/**
 * 意图语料库：维护「常见说法 → 标准术语」，查看命中率并做阈值调优预览。
 * 命中（精确或相似度 ≥ 阈值）直接返回标准术语，未命中才进入模型工作流。
 */
export function IntentCorpusCard() {
  const [stats, setStats] = useState<IntentCorpusStats | null>(null)
  const [entries, setEntries] = useState<IntentCorpusEntry[]>([])
  const [intent, setIntent] = useState('咨询')
  const [phrase, setPhrase] = useState('')
  const [previewText, setPreviewText] = useState('')
  const [preview, setPreview] = useState<IntentMatchPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  const load = useCallback(async () => {
    try {
      const data = await getIntentCorpus()
      setStats(data.stats)
      setEntries(data.entries ?? [])
    } catch {
      setStats(null)
      setEntries([])
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const handleAdd = async () => {
    if (!phrase.trim()) {
      setMessage('请先填写语料说法')
      return
    }
    setBusy(true)
    try {
      await addIntentCorpusEntry(intent, phrase.trim())
      setPhrase('')
      setMessage('已加入语料库并重建向量缓存')
      await load()
    } catch (err: any) {
      setMessage(err?.message || '新增失败')
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (corpusId: string) => {
    try {
      await deleteIntentCorpusEntry(corpusId)
      await load()
    } catch (err: any) {
      setMessage(err?.message || '删除失败')
    }
  }

  const handleRebuild = async () => {
    try {
      const size = await rebuildIntentCorpus()
      setMessage(`向量缓存已重建：${size} 条`)
      await load()
    } catch (err: any) {
      setMessage(err?.message || '重建失败')
    }
  }

  const handlePreview = async () => {
    if (!previewText.trim()) return
    try {
      setPreview(await previewIntentMatch(previewText.trim()))
    } catch (err: any) {
      setMessage(err?.message || '匹配预览失败')
    }
  }

  const sourceLabel: Record<string, string> = {
    corpus: '语料库命中',
    llm: '模型判定',
    heuristic: '启发式兜底',
    unanalyzed: '未分析',
  }

  return (
    <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
          📚 意图语料库（RAG 前置匹配）
          {stats && (
            <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
              条目 {stats.cacheSize} · 阈值 {stats.threshold} · 向量{' '}
              {stats.embeddingReady ? '就绪' : '未就绪（当前仅精确匹配）'} · 版本 {stats.cacheVersion}
            </span>
          )}
        </div>
        <button
          onClick={() => void handleRebuild()}
          className="rounded-lg border px-3 py-1.5 text-xs font-medium"
          style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
        >
          ♻️ 重建向量缓存
        </button>
      </div>

      {stats?.commentSources && stats.commentSources.length > 0 && (
        <div className="mb-3 flex flex-wrap gap-2 text-xs">
          {stats.commentSources.map((s) => (
            <span key={s.source} className="rounded-full px-3 py-1"
              style={{ background: s.source === 'corpus' ? '#E8FFEA' : '#F2F3F5',
                       color: s.source === 'corpus' ? '#00782C' : '#4E5969' }}>
              {sourceLabel[s.source] ?? s.source} · {s.cnt}
            </span>
          ))}
        </div>
      )}

      <div className="mb-3 flex flex-wrap items-end gap-3">
        <select
          value={intent}
          onChange={(e) => setIntent(e.target.value)}
          className="w-32 rounded-lg border px-3 py-2 text-sm outline-none"
          style={{ borderColor: '#E5E6EB' }}
        >
          {INTENT_OPTIONS.map((i) => (
            <option key={i} value={i}>{i}</option>
          ))}
        </select>
        <input
          value={phrase}
          onChange={(e) => setPhrase(e.target.value)}
          placeholder="常见说法，如「这个在哪里买」"
          className="w-72 rounded-lg border px-3 py-2 text-sm outline-none"
          style={{ borderColor: '#E5E6EB' }}
        />
        <button
          onClick={() => void handleAdd()}
          disabled={busy}
          className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          style={{ background: '#165DFF' }}
        >
          ＋ 加入语料
        </button>
      </div>

      <div className="mb-3 flex flex-wrap items-end gap-3 rounded-lg p-3" style={{ background: '#F7F8FA' }}>
        <div className="flex flex-col gap-1">
          <span className="text-xs" style={{ color: '#86909C' }}>匹配预览（验证阈值是否合适）</span>
          <input
            value={previewText}
            onChange={(e) => setPreviewText(e.target.value)}
            placeholder="粘贴一条评论，看看会命中哪条语料"
            className="w-96 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
        </div>
        <button
          onClick={() => void handlePreview()}
          className="rounded-lg border px-3 py-2 text-sm font-medium"
          style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
        >
          试算
        </button>
        {preview && (
          <div className="text-xs" style={{ color: '#4E5969' }}>
            {preview.matched
              ? `命中「${preview.intent}」（${preview.matchedBy}，相似度 ${preview.score}）`
              : `未达阈值，最高相似度 ${preview.score}（会走模型工作流）`}
            {preview.candidates.length > 0 && (
              <span style={{ color: '#86909C' }}>
                {' '}· 近邻：{preview.candidates.slice(0, 3).map((c) => `${c.phrase}(${c.score})`).join('、')}
              </span>
            )}
          </div>
        )}
      </div>

      {message && (
        <div className="mb-2 text-xs" style={{ color: '#165DFF' }}>{message}</div>
      )}

      <div className="max-h-56 space-y-1 overflow-y-auto">
        {entries.length === 0 && (
          <div className="text-xs" style={{ color: '#86909C' }}>语料库为空（首次启动会自动写入内置语料）</div>
        )}
        {entries.map((entry) => (
          <div key={entry.corpusId} className="flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs"
               style={{ borderColor: '#F2F3F5' }}>
            <span className="rounded px-2 py-0.5" style={{ background: '#FFF0F5', color: '#C40E3A' }}>
              {entry.intent}
            </span>
            <span style={{ color: '#1D2129' }}>{entry.phrase}</span>
            <span style={{ color: '#86909C' }}>
              {entry.source === 'SEED' ? '内置' : '自建'} · 命中 {entry.hitCount}
            </span>
            <button
              onClick={() => void handleDelete(entry.corpusId)}
              className="ml-auto rounded-lg border px-2 py-0.5"
              style={{ borderColor: '#E5E6EB', color: '#F53F3F' }}
            >
              删除
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}