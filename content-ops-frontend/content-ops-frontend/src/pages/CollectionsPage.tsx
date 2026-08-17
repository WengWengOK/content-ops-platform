import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import {
  listCollections,
  getCollection,
  createCollection,
  updateCollection,
  deleteCollection,
  removeWorkFromCollection,
} from '@/api/collections'
import type { WorkCollection } from '@/types'

const TYPE_OPTIONS = ['干货知识', '情感故事', '产品种草', '个人成长', '职场技能', '其他']
const STATUS_TO_CN: Record<string, string> = {
  COMPLETED: '已完成',
  FAILED: '失败',
  IN_PROGRESS: '生成中',
  AWAITING_HUMAN: '待确认',
  PENDING: '排队中',
  BUDGET_EXCEEDED: '预算超限',
}

export function CollectionsPage() {
  const [collections, setCollections] = useState<WorkCollection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [toast, setToast] = useState<{ msg: string; color: string } | null>(null)

  // 创建 / 编辑弹窗
  const [editing, setEditing] = useState<WorkCollection | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState({ name: '', type: TYPE_OPTIONS[0], description: '', customType: '' })
  const [saving, setSaving] = useState(false)

  // 详情视图
  const [detail, setDetail] = useState<WorkCollection | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      setCollections(await listCollections())
    } catch (err: any) {
      setError(err?.message || '加载作品合集失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const showToast = (msg: string, color = '#165DFF') => {
    setToast({ msg, color })
    setTimeout(() => setToast(null), 2600)
  }

  const openCreate = () => {
    setEditing(null)
    setForm({ name: '', type: TYPE_OPTIONS[0], description: '', customType: '' })
    setModalOpen(true)
  }

  const openEdit = (c: WorkCollection) => {
    setEditing(c)
    setForm({
      name: c.name,
      type: TYPE_OPTIONS.includes(c.type) ? c.type : '其他',
      description: c.description ?? '',
      customType: TYPE_OPTIONS.includes(c.type) ? '' : c.type,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const type = form.type === '其他' && form.customType.trim() ? form.customType.trim() : form.type
    if (!form.name.trim() || !type) {
      showToast('请填写合集名称和类型', '#FF7D00')
      return
    }
    setSaving(true)
    try {
      if (editing) {
        await updateCollection(editing.collectionId, {
          name: form.name.trim(),
          type,
          description: form.description,
        })
        showToast('合集已更新', '#00B42A')
      } else {
        await createCollection({
          name: form.name.trim(),
          type,
          description: form.description,
        })
        showToast('合集已创建', '#00B42A')
      }
      setModalOpen(false)
      await load()
      if (detail) {
        const fresh = await getCollection(detail.collectionId)
        setDetail(fresh)
      }
    } catch (err: any) {
      showToast(err?.message || '保存失败', '#F53F3F')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (c: WorkCollection) => {
    if (!window.confirm(`确认删除合集「${c.name}」？合集内作品的归属关系也会移除（作品本身不受影响）。`)) {
      return
    }
    try {
      await deleteCollection(c.collectionId)
      showToast('合集已删除', '#00B42A')
      if (detail?.collectionId === c.collectionId) setDetail(null)
      await load()
    } catch (err: any) {
      showToast(err?.message || '删除失败', '#F53F3F')
    }
  }

  const openDetail = async (c: WorkCollection) => {
    setDetailLoading(true)
    try {
      const fresh = await getCollection(c.collectionId)
      setDetail(fresh)
    } catch (err: any) {
      showToast(err?.message || '加载合集详情失败', '#F53F3F')
    } finally {
      setDetailLoading(false)
    }
  }

  const handleRemoveWork = async (workflowId: string) => {
    if (!detail) return
    try {
      await removeWorkFromCollection(detail.collectionId, workflowId)
      showToast('作品已移出合集', '#00B42A')
      const fresh = await getCollection(detail.collectionId)
      setDetail(fresh)
      await load()
    } catch (err: any) {
      showToast(err?.message || '移除失败', '#F53F3F')
    }
  }

  return (
    <Layout activeNav="collections" breadcrumbs={[{ label: '作品合集' }]}>
      <style>{`
        .collection-card { transition: all 150ms ease; }
        .collection-card:hover { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(0,0,0,0.06); }
      `}</style>

      <div className="mx-auto max-w-6xl p-6">
        {/* 头部 */}
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold" style={{ color: '#1D2129' }}>
              作品合集
            </h1>
            <p className="mt-1 text-sm" style={{ color: '#86909C' }}>
              按类型归集同类作品，创建作品时或生成后都可指定放入合集
            </p>
          </div>
          <button
            onClick={openCreate}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors"
            style={{ background: '#165DFF' }}
          >
            + 新建合集
          </button>
        </div>

        {error && (
          <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: '#F53F3F', color: '#F53F3F', background: '#FFF0F0' }}>
            {error}
          </div>
        )}

        {detail ? (
          /* 详情视图 */
          <div>
            <button
              onClick={() => setDetail(null)}
              className="mb-4 text-sm font-medium"
              style={{ color: '#165DFF', background: 'none', border: 'none', cursor: 'pointer' }}
            >
              ← 返回合集列表
            </button>
            <div className="mb-4 flex flex-wrap items-center gap-3">
              <h2 className="text-lg font-bold" style={{ color: '#1D2129' }}>
                {detail.name}
              </h2>
              <span
                className="rounded-full px-3 py-1 text-xs font-medium"
                style={{ background: '#FFF0F5', color: '#C40E3A' }}
              >
                {detail.type}
              </span>
              <span className="text-xs" style={{ color: '#86909C' }}>
                {detail.works?.length ?? 0} 个作品
              </span>
              <button
                onClick={() => openEdit(detail)}
                className="text-xs font-medium"
                style={{ color: '#165DFF', background: 'none', border: 'none', cursor: 'pointer' }}
              >
                编辑
              </button>
            </div>
            {detail.description && (
              <p className="mb-4 text-sm" style={{ color: '#4E5969' }}>
                {detail.description}
              </p>
            )}

            {detailLoading ? (
              <div className="py-12 text-center text-sm" style={{ color: '#86909C' }}>加载中…</div>
            ) : (detail.works ?? []).length === 0 ? (
              <div className="card py-12 text-center text-sm" style={{ color: '#C9CDD4' }}>
                合集还是空的，去工作流详情页点「加入合集」把作品放进来
              </div>
            ) : (
              <div className="space-y-3">
                {detail.works!.map((w) => (
                  <div
                    key={w.workflowId}
                    className="card flex items-center justify-between p-4"
                  >
                    <div className="min-w-0">
                      <Link
                        to={`/workflow-detail?workflowId=${w.workflowId}`}
                        className="block truncate text-sm font-medium"
                        style={{ color: '#1D2129' }}
                      >
                        {w.title || '未命名作品'}
                      </Link>
                      <div className="mt-1 flex flex-wrap items-center gap-2 text-xs" style={{ color: '#86909C' }}>
                        <span
                          className="rounded px-2 py-0.5"
                          style={{
                            background: w.status === 'COMPLETED' ? '#E8FFEA' : '#F2F3F5',
                            color: w.status === 'COMPLETED' ? '#00B42A' : '#4E5969',
                          }}
                        >
                          {STATUS_TO_CN[w.status ?? ''] ?? w.status ?? '未知'}
                        </span>
                        {(w.platforms ?? []).length > 0 && <span>{w.platforms!.join(' / ')}</span>}
                        {w.createdAt && <span>{String(w.createdAt).slice(0, 10)}</span>}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Link
                        to={`/workflow-detail?workflowId=${w.workflowId}`}
                        className="rounded-lg px-3 py-1.5 text-xs font-medium"
                        style={{ background: '#E8F3FF', color: '#165DFF' }}
                      >
                        查看
                      </Link>
                      <button
                        onClick={() => handleRemoveWork(w.workflowId)}
                        className="rounded-lg px-3 py-1.5 text-xs font-medium transition-colors"
                        style={{ background: '#F2F3F5', color: '#86909C', cursor: 'pointer' }}
                      >
                        移除
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : (
          /* 列表视图 */
          loading ? (
            <div className="py-16 text-center text-sm" style={{ color: '#86909C' }}>加载中…</div>
          ) : collections.length === 0 ? (
            <div className="card py-16 text-center">
              <p className="text-sm" style={{ color: '#86909C' }}>还没有作品合集</p>
              <p className="mt-2 text-xs" style={{ color: '#C9CDD4' }}>
                点击右上角「新建合集」，按类型（干货/情感/种草等）建立自己的内容资产库
              </p>
              <button
                onClick={openCreate}
                className="mt-4 rounded-lg px-4 py-2 text-sm font-medium text-white"
                style={{ background: '#165DFF', border: 'none', cursor: 'pointer' }}
              >
                创建第一个合集
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
              {collections.map((c) => (
                <div key={c.collectionId} className="collection-card card p-5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <h3 className="truncate text-base font-semibold" style={{ color: '#1D2129' }}>
                        {c.name}
                      </h3>
                      <span
                        className="mt-2 inline-block rounded-full px-2.5 py-0.5 text-xs font-medium"
                        style={{ background: '#FFF0F5', color: '#C40E3A' }}
                      >
                        {c.type}
                      </span>
                    </div>
                    <span className="text-sm font-bold" style={{ color: '#165DFF' }}>
                      {c.workCount ?? 0}
                    </span>
                  </div>
                  <p className="mt-3 line-clamp-2 min-h-[36px] text-xs" style={{ color: '#86909C' }}>
                    {c.description || '暂无描述'}
                  </p>
                  <div className="mt-4 flex items-center gap-2">
                    <button
                      onClick={() => openDetail(c)}
                      className="flex-1 rounded-lg py-2 text-xs font-medium text-white"
                      style={{ background: '#165DFF', border: 'none', cursor: 'pointer' }}
                    >
                      查看作品
                    </button>
                    <button
                      onClick={() => openEdit(c)}
                      className="rounded-lg px-3 py-2 text-xs font-medium"
                      style={{ background: '#E8F3FF', color: '#165DFF', border: 'none', cursor: 'pointer' }}
                    >
                      编辑
                    </button>
                    <button
                      onClick={() => handleDelete(c)}
                      className="rounded-lg px-3 py-2 text-xs font-medium"
                      style={{ background: '#FFF0F0', color: '#F53F3F', border: 'none', cursor: 'pointer' }}
                    >
                      删除
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )
        )}
      </div>

      {/* 创建 / 编辑弹窗 */}
      {modalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ background: 'rgba(0,0,0,0.35)' }}
          onClick={() => setModalOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-4 text-base font-bold" style={{ color: '#1D2129' }}>
              {editing ? '编辑合集' : '新建作品合集'}
            </h3>

            <label className="mb-1 block text-xs font-medium" style={{ color: '#4E5969' }}>
              合集名称
            </label>
            <input
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="例如：职场干货 / 旅行灵感"
              className="mb-4 w-full rounded-lg border px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-[#165DFF]"
              style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
            />

            <label className="mb-1 block text-xs font-medium" style={{ color: '#4E5969' }}>
              合集类型（按类型区分）
            </label>
            <div className="mb-3 flex flex-wrap gap-2">
              {TYPE_OPTIONS.map((t) => (
                <button
                  key={t}
                  onClick={() => setForm({ ...form, type: t })}
                  className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
                  style={{
                    background: form.type === t ? '#165DFF' : '#F2F3F5',
                    color: form.type === t ? '#fff' : '#4E5969',
                    border: 'none',
                    cursor: 'pointer',
                  }}
                >
                  {t}
                </button>
              ))}
            </div>
            {form.type === '其他' && (
              <input
                value={form.customType}
                onChange={(e) => setForm({ ...form, customType: e.target.value })}
                placeholder="自定义类型，如：读书笔记"
                className="mb-4 w-full rounded-lg border px-3 py-2 text-sm outline-none"
                style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
              />
            )}

            <label className="mb-1 block text-xs font-medium" style={{ color: '#4E5969' }}>
              描述（可选）
            </label>
            <textarea
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="这个合集用来放什么类型的作品…"
              rows={2}
              className="mb-5 w-full resize-none rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB', background: '#F7F8FA' }}
            />

            <div className="flex justify-end gap-2">
              <button
                onClick={() => setModalOpen(false)}
                className="rounded-lg px-4 py-2 text-sm font-medium"
                style={{ background: '#F2F3F5', color: '#4E5969', border: 'none', cursor: 'pointer' }}
              >
                取消
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                className="rounded-lg px-4 py-2 text-sm font-medium text-white"
                style={{ background: saving ? '#A0CFFF' : '#165DFF', border: 'none', cursor: saving ? 'not-allowed' : 'pointer' }}
              >
                {saving ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toast */}
      {toast && (
        <div
          className="fixed right-6 top-6 z-[100] flex items-center gap-2 rounded-lg border px-4 py-3 shadow-lg"
          style={{ background: '#fff', borderColor: toast.color }}
        >
          <span className="text-sm" style={{ color: '#1D2129' }}>{toast.msg}</span>
        </div>
      )}
    </Layout>
  )
}
