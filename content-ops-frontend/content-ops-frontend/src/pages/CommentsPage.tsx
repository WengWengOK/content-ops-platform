import { useCallback, useEffect, useState } from 'react'
import { Layout } from '@/components/layout/Layout'
import {
  addCommentWatch,
  analyzeAllComments,
  analyzeComment,
  approveCommentReply,
  chatCommentReply,
  collectCommentNotifications,
  collectComments,
  getCommentOpsOverview,
  getCommentScheduler,
  getCommentSourceStatus,
  getCommentStats,
  listComments,
  listCommentWatches,
  removeCommentWatch,
  runAllCommentWatches,
  runCommentWatch,
  sendCommentReply,
  setCommentWatchEnabled,
  updateCommentReply,
  updateCommentWatchToken,
} from '@/api/comments'
import {
  createCredential,
  deleteCredential,
  listCredentials,
  probeCredential,
  rotateCredentials,
  updateCredential,
} from '@/api/credentials'
import type {
  CommentSchedulerStatus,
  CommentSourceStatus,
  CommentStats,
  CommentWatch,
  CredentialProbeResult,
  OpsOverview,
  PlatformComment,
  PlatformCredential,
} from '@/types'

const INTENTS = ['咨询', '求教程', '售后', '吐槽', '表扬', '推广', '潜在客户', '反馈', '无关']
const SENTIMENTS = ['POSITIVE', 'NEUTRAL', 'NEGATIVE']

const INTENT_COLORS: Record<string, string> = {
  咨询: '#165DFF',
  求教程: '#0FC6C2',
  售后: '#F53F3F',
  吐槽: '#F53F3F',
  表扬: '#00B42A',
  推广: '#FF7D00',
  潜在客户: '#722ED1',
  反馈: '#FF9A2E',
  无关: '#86909C',
}

const SENTIMENT_COLORS: Record<string, string> = {
  POSITIVE: '#00B42A',
  NEUTRAL: '#86909C',
  NEGATIVE: '#F53F3F',
}

const STATUS_CN: Record<string, string> = {
  NONE: '未处理',
  DRAFT: '草稿',
  APPROVED: '已审核',
  SENT: '已发送',
}

interface DialogTurn {
  role: 'user' | 'assistant'
  content: string
}

export function CommentsPage() {
  const [platform, setPlatform] = useState('xiaohongshu')
  const [workId, setWorkId] = useState('')
  const [intent, setIntent] = useState('')
  const [sentiment, setSentiment] = useState('')
  const [comments, setComments] = useState<PlatformComment[]>([])
  const [stats, setStats] = useState<CommentStats>({ intent: [], sentiment: [] })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [toast, setToast] = useState<{ msg: string; color: string } | null>(null)

  // AI 对话面板
  const [chatComment, setChatComment] = useState<PlatformComment | null>(null)
  const [chatInput, setChatInput] = useState('')
  const [chatBusy, setChatBusy] = useState(false)
  const [dialogTurns, setDialogTurns] = useState<DialogTurn[]>([])
  const [editReply, setEditReply] = useState('')

  // 自动采集监控
  const [watches, setWatches] = useState<CommentWatch[]>([])
  const [scheduler, setScheduler] = useState<CommentSchedulerStatus | null>(null)
  const [sourceStatus, setSourceStatus] = useState<CommentSourceStatus | null>(null)
  const [watchInput, setWatchInput] = useState('')
  const [watchXsecToken, setWatchXsecToken] = useState('')
  const [watchAutoAnalyze, setWatchAutoAnalyze] = useState(true)
  const [watchBusy, setWatchBusy] = useState('')
  const [unread, setUnread] = useState<Record<string, number>>({})
  const [notifBusy, setNotifBusy] = useState(false)

  // 平台账号凭据（多账号归属）
  const [credentials, setCredentials] = useState<PlatformCredential[]>([])
  const [selectedCredential, setSelectedCredential] = useState('')
  const [credName, setCredName] = useState('')
  const [credBaseUrl, setCredBaseUrl] = useState('')
  const [credToken, setCredToken] = useState('')
  const [credDefault, setCredDefault] = useState(false)
  const [credBusy, setCredBusy] = useState(false)
  const [probes, setProbes] = useState<Record<string, CredentialProbeResult>>({})
  const [tokenEdits, setTokenEdits] = useState<Record<string, string>>({})
  const [ops, setOps] = useState<OpsOverview | null>(null)
  const [opsBusy, setOpsBusy] = useState(false)

  const showToast = (msg: string, color = '#165DFF') => {
    setToast({ msg, color })
    setTimeout(() => setToast(null), 2600)
  }

  const loadComments = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const res = await listComments({
        platform: platform || undefined,
        workId: workId || undefined,
        intent: intent || undefined,
        sentiment: sentiment || undefined,
        limit: 100,
      })
      setComments(res.comments ?? [])
    } catch (err: any) {
      setError(err?.message || '评论加载失败')
      setComments([])
    } finally {
      setLoading(false)
    }
  }, [platform, workId, intent, sentiment])

  const loadStats = useCallback(async () => {
    try {
      setStats(await getCommentStats(platform || undefined, workId || undefined))
    } catch {
      setStats({ intent: [], sentiment: [] })
    }
  }, [platform, workId])

  useEffect(() => {
    void loadComments()
    void loadStats()
  }, [loadComments, loadStats])

  const handleCollect = async () => {
    if (!workId.trim()) {
      showToast('请先输入作品 ID（workId）', '#F53F3F')
      return
    }
    try {
      const res = await collectComments(workId.trim(), platform || 'xiaohongshu', selectedCredential || undefined)
      const srcLabel = res.source === 'api' ? '真实接口' : '模拟数据'
      showToast(`采集完成（${srcLabel}）：新增 ${res.inserted}/${res.collected} 条评论`, '#00B42A')
      await loadComments()
      await loadStats()
      await loadWatches()
    } catch (err: any) {
      showToast(err?.message || '采集失败', '#F53F3F')
    }
  }

  const handleAnalyzeAll = async () => {
    try {
      const res = await analyzeAllComments({ workId: workId || undefined, platform: platform || undefined })
      showToast(`AI 分析完成：${res.analyzed} 条`, '#00B42A')
      await loadComments()
      await loadStats()
    } catch (err: any) {
      showToast(err?.message || '批量分析失败', '#F53F3F')
    }
  }

  const handleAnalyzeOne = async (id: string) => {
    try {
      const updated = await analyzeComment(id)
      setComments((prev) => prev.map((c) => (c.commentId === id ? updated : c)))
      showToast('分析完成，已生成回复草稿', '#00B42A')
      await loadStats()
    } catch (err: any) {
      showToast(err?.message || '分析失败', '#F53F3F')
    }
  }

  const openChat = (comment: PlatformComment) => {
    setChatComment(comment)
    setEditReply(comment.aiReply || '')
    setDialogTurns(parseDialog(comment.dialogHistory))
  }

  const parseDialog = (json?: string): DialogTurn[] => {
    if (!json) return []
    try {
      const arr = JSON.parse(json) as DialogTurn[]
      return Array.isArray(arr) ? arr : []
    } catch {
      return []
    }
  }

  const handleChatSend = async () => {
    const message = chatInput.trim()
    if (!chatComment || !message || chatBusy) return
    setChatBusy(true)
    const optimistic: DialogTurn[] = [...dialogTurns, { role: 'user', content: message }]
    setDialogTurns(optimistic)
    setChatInput('')
    try {
      const updated = await chatCommentReply(chatComment.commentId, message)
      setChatComment(updated)
      setDialogTurns(parseDialog(updated.dialogHistory))
      setEditReply(updated.aiReply || '')
      setComments((prev) =>
        prev.map((c) => (c.commentId === updated.commentId ? updated : c))
      )
    } catch (err: any) {
      showToast(err?.message || '对话失败', '#F53F3F')
      setDialogTurns(dialogTurns)
    } finally {
      setChatBusy(false)
    }
  }

  const handleSaveReply = async () => {
    if (!chatComment) return
    try {
      const updated = await updateCommentReply(chatComment.commentId, { reply: editReply, status: 'DRAFT' })
      setChatComment(updated)
      setComments((prev) => prev.map((c) => (c.commentId === updated.commentId ? updated : c)))
      showToast('回复草稿已保存', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '保存失败', '#F53F3F')
    }
  }

  const handleApprove = async (id: string) => {
    try {
      const updated = await approveCommentReply(id)
      setComments((prev) => prev.map((c) => (c.commentId === id ? updated : c)))
      if (chatComment?.commentId === id) setChatComment(updated)
      showToast('已审核通过', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '审核失败', '#F53F3F')
    }
  }

  const handleSend = async (id: string) => {
    try {
      const res = await sendCommentReply(id)
      const updated = res.comment
      setComments((prev) => prev.map((c) => (c.commentId === id ? updated : c)))
      if (chatComment?.commentId === id) setChatComment(updated)
      showToast(
        res.sendMode === 'real' ? `已真实回复到平台：${res.message}` : `模拟发送：${res.message}`,
        res.sendMode === 'real' ? '#00B42A' : '#FF7D00'
      )
    } catch (err: any) {
      showToast(err?.message || '发送失败', '#F53F3F')
    }
  }

  const loadWatches = useCallback(async () => {
    try {
      setWatches(await listCommentWatches())
    } catch {
      setWatches([])
    }
  }, [])

  const loadScheduler = useCallback(async () => {
    try {
      setScheduler(await getCommentScheduler())
    } catch {
      setScheduler(null)
    }
  }, [])

  const loadSourceStatus = useCallback(async () => {
    try {
      setSourceStatus(await getCommentSourceStatus())
    } catch {
      setSourceStatus(null)
    }
  }, [])

  const loadCredentials = useCallback(async () => {
    try {
      setCredentials(await listCredentials(true))
    } catch {
      setCredentials([])
    }
  }, [])

  const loadOps = useCallback(async () => {
    try {
      setOps(await getCommentOpsOverview())
    } catch {
      setOps(null)
    }
  }, [])

  useEffect(() => {
    void loadWatches()
    void loadScheduler()
    void loadSourceStatus()
    void loadCredentials()
    void loadOps()
  }, [loadWatches, loadScheduler, loadSourceStatus, loadCredentials, loadOps])

  const handleRotateKeys = async () => {
    setOpsBusy(true)
    try {
      const report = await rotateCredentials()
      showToast(
        `轮换完成：共 ${report.total} 条，已轮换 ${report.rotated}，跳过 ${report.skipped}` +
          (report.failed > 0 ? `，失败 ${report.failed}` : ''),
        report.failed > 0 ? '#FF7D00' : '#00B42A'
      )
      await loadOps()
      await loadCredentials()
    } catch (err: any) {
      showToast(err?.message || '轮换失败', '#F53F3F')
    } finally {
      setOpsBusy(false)
    }
  }

  const credentialName = (credentialId?: string) => {
    if (!credentialId) return '全局配置'
    return credentials.find((c) => c.credentialId === credentialId)?.accountName ?? credentialId.slice(0, 8)
  }

  const handleCreateCredential = async () => {
    if (!credBaseUrl.trim()) {
      showToast('请先填写桥地址（baseUrl）', '#F53F3F')
      return
    }
    setCredBusy(true)
    try {
      await createCredential({
        accountName: credName.trim() || '未命名账号',
        baseUrl: credBaseUrl.trim(),
        accessToken: credToken.trim() || undefined,
        defaultCredential: credDefault,
      })
      setCredName('')
      setCredBaseUrl('')
      setCredToken('')
      setCredDefault(false)
      await loadCredentials()
      showToast('账号凭据已添加（令牌已加密存储）', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '添加账号失败', '#F53F3F')
    } finally {
      setCredBusy(false)
    }
  }

  const handleProbeCredential = async (credentialId: string) => {
    try {
      const result = await probeCredential(credentialId)
      setProbes((prev) => ({ ...prev, [credentialId]: result }))
      showToast(result.hint, result.authenticated ? '#00B42A' : '#FF7D00')
    } catch (err: any) {
      showToast(err?.message || '测试连接失败', '#F53F3F')
    }
  }

  const handleSetDefaultCredential = async (credentialId: string) => {
    try {
      await updateCredential(credentialId, { defaultCredential: true, enabled: true })
      await loadCredentials()
      showToast('已设为默认账号', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '设置失败', '#F53F3F')
    }
  }

  const handleToggleCredential = async (credential: PlatformCredential) => {
    try {
      await updateCredential(credential.credentialId, { enabled: !credential.enabled })
      await loadCredentials()
      showToast(credential.enabled ? '已停用该账号' : '已启用该账号', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '操作失败', '#F53F3F')
    }
  }

  const handleDeleteCredential = async (credentialId: string) => {
    try {
      await deleteCredential(credentialId)
      if (selectedCredential === credentialId) setSelectedCredential('')
      await loadCredentials()
      showToast('账号凭据已删除', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '删除失败', '#F53F3F')
    }
  }

  const handleAddWatch = async () => {
    const id = watchInput.trim()
    if (!id) {
      showToast('请先输入要监控的作品 ID', '#F53F3F')
      return
    }
    try {
      await addCommentWatch({
        workId: id,
        platform: platform || 'xiaohongshu',
        autoAnalyze: watchAutoAnalyze,
        xsecToken: watchXsecToken.trim() || undefined,
        credentialId: selectedCredential || undefined,
      })
      setWatchInput('')
      setWatchXsecToken('')
      await loadWatches()
      await loadScheduler()
      showToast(`已加入自动采集监控：${id}`, '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '加入监控失败', '#F53F3F')
    }
  }

  const handleUpdateWatchToken = async (watch: CommentWatch) => {
    const token = (tokenEdits[watch.watchId] ?? '').trim()
    if (!token) {
      showToast('请先粘贴新的 xsec_token', '#F53F3F')
      return
    }
    try {
      await updateCommentWatchToken(watch.watchId, token)
      setTokenEdits((prev) => ({ ...prev, [watch.watchId]: '' }))
      await loadWatches()
      showToast('票据已更新，状态重置为健康', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '更新票据失败', '#F53F3F')
    }
  }

  const handleToggleWatch = async (watch: CommentWatch) => {
    try {
      await setCommentWatchEnabled(watch.watchId, !watch.enabled)
      await loadWatches()
      await loadScheduler()
      showToast(watch.enabled ? '已暂停监控' : '已启用监控', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '操作失败', '#F53F3F')
    }
  }

  const handleRemoveWatch = async (watchId: string) => {
    try {
      await removeCommentWatch(watchId)
      await loadWatches()
      await loadScheduler()
      showToast('已移除监控作品', '#00B42A')
    } catch (err: any) {
      showToast(err?.message || '移除失败', '#F53F3F')
    }
  }

  const handleRunWatch = async (watchId: string) => {
    setWatchBusy(watchId)
    try {
      const res = await runCommentWatch(watchId)
      const srcLabel = res.source === 'api' ? '真实接口' : res.source === 'mock' ? '模拟数据' : '无数据'
      showToast(`采集完成（${srcLabel}）：新增 ${res.inserted} 条，分析 ${res.analyzed} 条`, '#00B42A')
      await loadWatches()
      await loadComments()
      await loadStats()
      await loadScheduler()
    } catch (err: any) {
      showToast(err?.message || '采集失败', '#F53F3F')
      await loadWatches()
    } finally {
      setWatchBusy('')
    }
  }

  const handleCollectNotifications = async () => {
    setNotifBusy(true)
    try {
      const res = await collectCommentNotifications({ limit: 20, autoAnalyze: true })
      if (res.source !== 'api') {
        showToast(`未拉取到通知：${res.fallbackReason || '通知接口未配置'}`, '#FF7D00')
      } else {
        setUnread(res.unread || {})
        showToast(
          `通知增量：通知 ${res.notifications} 条，新增评论 ${res.inserted}，分析 ${res.analyzed}` +
            (res.createdWatches > 0 ? `，新纳入监控 ${res.createdWatches} 篇` : '') +
            (res.filtered > 0 ? `（平台过滤 ${res.filtered} 条已删除/异常）` : ''),
          res.inserted > 0 ? '#00B42A' : '#165DFF'
        )
      }
      await loadWatches()
      await loadComments()
      await loadStats()
      await loadScheduler()
    } catch (err: any) {
      showToast(err?.message || '拉取通知失败', '#F53F3F')
    } finally {
      setNotifBusy(false)
    }
  }

  const handleRunAll = async () => {
    try {
      const run = await runAllCommentWatches()
      showToast(
        `批量采集完成：作品 ${run.worksScanned}，新增 ${run.commentsNew}，分析 ${run.analyzed}`,
        run.failed > 0 ? '#FF7D00' : '#00B42A'
      )
      await loadWatches()
      await loadComments()
      await loadStats()
      await loadScheduler()
    } catch (err: any) {
      showToast(err?.message || '批量采集失败', '#F53F3F')
    }
  }

  const timeStr = (t?: string) => {
    if (!t) return ''
    return new Date(t).toLocaleString('zh-CN', { hour12: false })
  }

  return (
    <Layout pageTitle="评论区 AI 助手" breadcrumbs={[{ label: '评论区 AI 助手' }]} activeNav="comments">
      {/* 控制区 */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>作品 ID（workId）</span>
            <input
              value={workId}
              onChange={(e) => setWorkId(e.target.value)}
              placeholder="如 9f2c…，留空查看全部"
              className="w-56 rounded-lg border px-3 py-2 text-sm outline-none focus:border-[#FF2D5E]"
              style={{ borderColor: '#E5E6EB' }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>平台</span>
            <select
              value={platform}
              onChange={(e) => setPlatform(e.target.value)}
              className="w-36 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              <option value="xiaohongshu">小红书</option>
              <option value="douyin">抖音</option>
              <option value="wechat">微信</option>
              <option value="kuaishou">快手</option>
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>意图</span>
            <select
              value={intent}
              onChange={(e) => setIntent(e.target.value)}
              className="w-32 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              {INTENTS.map((i) => (
                <option key={i} value={i}>{i}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>情感</span>
            <select
              value={sentiment}
              onChange={(e) => setSentiment(e.target.value)}
              className="w-32 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全部</option>
              {SENTIMENTS.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </div>
          <button
            onClick={handleCollect}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white"
            style={{ background: '#FF2D5E' }}
          >
            📥 采集评论
          </button>
          <button
            onClick={handleAnalyzeAll}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white"
            style={{ background: '#165DFF' }}
          >
            ✨ AI 批量分析
          </button>
          <button
            onClick={() => { void loadComments(); void loadStats() }}
            className="rounded-lg border px-4 py-2 text-sm font-medium"
            style={{ borderColor: '#E5E6EB', color: '#4E5969' }}
          >
            刷新
          </button>
        </div>
        {error && (
          <div className="mt-3 rounded-lg px-3 py-2 text-sm" style={{ background: '#FFF0F0', color: '#F53F3F' }}>
            {error}
          </div>
        )}
      </div>

      {/* 运维总览：密钥轮换 / 账号健康 / 票据预警 */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
            🩺 运维总览
            {ops && (
              <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
                密钥 {ops.rotation.encryptionEnabled ? ops.rotation.keyId : '未启用加密'} · 待轮换 {ops.rotation.pendingRotation}
                {' · '}监控 {ops.watchHealth.enabled}/{ops.watchHealth.total} 启用
                {' · '}票据过期 {ops.watchHealth.expired} · 即将过期 {ops.watchHealth.expiringSoon}
                （阈值 {ops.watchHealth.warnDays} 天）
              </span>
            )}
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => void loadOps()}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium"
              style={{ borderColor: '#E5E6EB', color: '#4E5969' }}
            >
              刷新
            </button>
            <button
              onClick={() => void handleRotateKeys()}
              disabled={opsBusy || !ops || ops.rotation.pendingRotation === 0}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
            >
              {opsBusy ? '轮换中…' : `🔐 执行密钥轮换（${ops?.rotation.pendingRotation ?? 0}）`}
            </button>
          </div>
        </div>

        {ops?.rotation.hint && (
          <div className="mb-3 rounded-lg px-3 py-2 text-xs" style={{ background: '#F7F8FA', color: '#4E5969' }}>
            轮换状态：{ops.rotation.hint}
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div>
            <div className="mb-2 text-xs font-medium" style={{ color: '#1D2129' }}>账号健康</div>
            <div className="space-y-1">
              {(!ops || ops.credentials.length === 0) && (
                <div className="text-xs" style={{ color: '#86909C' }}>暂无账号凭据</div>
              )}
              {ops?.credentials.map((c) => (
                <div
                  key={c.credentialId}
                  className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-1.5 text-xs"
                  style={{ borderColor: '#F2F3F5' }}
                >
                  <span className="font-medium" style={{ color: '#1D2129' }}>{c.accountName}</span>
                  {c.needsRotation && (
                    <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>待轮换</span>
                  )}
                  {(c.tokenState === 'AUTH_INVALID' || c.tokenState === 'ERROR') && (
                    <span className="rounded px-2 py-0.5" style={{ background: '#FFECE8', color: '#F53F3F' }}>需更新令牌</span>
                  )}
                  {c.tokenState === 'OK' && (
                    <span className="rounded px-2 py-0.5" style={{ background: '#E8FFEA', color: '#00782C' }}>已登录</span>
                  )}
                  <span style={{ color: '#86909C' }}>{c.baseUrl} · token {c.accessTokenMasked || '（未设置）'}</span>
                </div>
              ))}
            </div>
          </div>

          <div>
            <div className="mb-2 text-xs font-medium" style={{ color: '#1D2129' }}>票据预警（需要更新的监控作品）</div>
            <div className="space-y-1">
              {(!ops || ops.watchHealth.problems.length === 0) && (
                <div className="text-xs" style={{ color: '#86909C' }}>没有需要处理的票据</div>
              )}
              {ops?.watchHealth.problems.slice(0, 6).map((w) => (
                <div
                  key={w.watchId}
                  className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-1.5 text-xs"
                  style={{ borderColor: '#F2F3F5' }}
                >
                  <span className="font-medium" style={{ color: '#1D2129' }}>{w.workId}</span>
                  <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>{w.accountName}</span>
                  <span
                    className="rounded px-2 py-0.5"
                    style={w.warning === 'EXPIRED'
                      ? { background: '#FFECE8', color: '#F53F3F' }
                      : { background: '#FFF7E8', color: '#9C5B00' }}
                  >
                    {w.warning === 'EXPIRED' ? '已过期' : `即将过期（已用 ${w.tokenAgeDays ?? '?'} 天）`}
                  </span>
                  {w.observedTtlDays ? (
                    <span style={{ color: '#86909C' }}>观测有效期 {w.observedTtlDays} 天</span>
                  ) : null}
                </div>
              ))}
              {ops && ops.watchHealth.problems.length > 6 && (
                <div className="text-xs" style={{ color: '#86909C' }}>
                  还有 {ops.watchHealth.problems.length - 6} 条，见下方监控列表
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 平台账号凭据（多账号归属） */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 text-sm font-medium" style={{ color: '#1D2129' }}>
          🔑 平台账号凭据
          <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
            共 {credentials.length} 个账号 · 采集/回复/通知按账号归属，令牌加密存储且只显示掩码
          </span>
        </div>

        <div className="mb-3 flex flex-wrap items-end gap-3">
          <input
            value={credName}
            onChange={(e) => setCredName(e.target.value)}
            placeholder="账号名，如 主号·小红"
            className="w-40 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
          <input
            value={credBaseUrl}
            onChange={(e) => setCredBaseUrl(e.target.value)}
            placeholder="桥地址，如 http://127.0.0.1:18060"
            className="w-72 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
          <input
            value={credToken}
            onChange={(e) => setCredToken(e.target.value)}
            type="password"
            placeholder="桥的 AUTH_TOKEN"
            className="w-56 rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: '#E5E6EB' }}
          />
          <label className="flex items-center gap-2 pb-2 text-xs" style={{ color: '#4E5969' }}>
            <input type="checkbox" checked={credDefault} onChange={(e) => setCredDefault(e.target.checked)} />
            设为默认账号
          </label>
          <button
            onClick={() => void handleCreateCredential()}
            disabled={credBusy}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            style={{ background: '#165DFF' }}
          >
            {credBusy ? '添加中…' : '＋ 添加账号'}
          </button>
        </div>

        <div className="space-y-2">
          {credentials.length === 0 && (
            <div className="text-xs" style={{ color: '#86909C' }}>
              还没有账号凭据。添加后「加入监控」可选择该作品属于哪个账号；未配置时回退全局配置。
            </div>
          )}
          {credentials.map((c) => (
            <div
              key={c.credentialId}
              className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-xs"
              style={{ borderColor: '#F2F3F5' }}
            >
              <span className="font-medium" style={{ color: '#1D2129' }}>{c.accountName}</span>
              {c.defaultCredential && (
                <span className="rounded px-2 py-0.5" style={{ background: '#E8F3FF', color: '#165DFF' }}>默认</span>
              )}
              <span
                className="rounded px-2 py-0.5"
                style={{ background: c.enabled ? '#E8FFEA' : '#F2F3F5', color: c.enabled ? '#00782C' : '#86909C' }}
              >
                {c.enabled ? '启用' : '停用'}
              </span>
              <span style={{ color: '#86909C' }}>
                {c.baseUrl} · token {c.accessTokenMasked || '（未设置）'}
              </span>
              {probes[c.credentialId] && (
                <span style={{ color: probes[c.credentialId].authenticated ? '#00782C' : '#9C5B00' }}>
                  {probes[c.credentialId].hint}
                </span>
              )}
              {(c.tokenState === 'AUTH_INVALID' || c.tokenState === 'ERROR') && (
                <span className="rounded px-2 py-0.5" style={{ background: '#FFECE8', color: '#F53F3F' }}>
                  ⚠️ 需更新令牌（AUTH_TOKEN 失效或服务不可达）
                </span>
              )}
              {c.lastError && <span style={{ color: '#F53F3F' }}>⚠️ {c.lastError}</span>}
              <span className="ml-auto flex gap-2">
                <button
                  onClick={() => void handleProbeCredential(c.credentialId)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#0FC6C2' }}
                >
                  测试连接
                </button>
                <button
                  onClick={() => void handleSetDefaultCredential(c.credentialId)}
                  disabled={c.defaultCredential}
                  className="rounded-lg border px-2 py-1 disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
                >
                  设为默认
                </button>
                <button
                  onClick={() => void handleToggleCredential(c)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#FF7D00' }}
                >
                  {c.enabled ? '停用' : '启用'}
                </button>
                <button
                  onClick={() => void handleDeleteCredential(c.credentialId)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#F53F3F' }}
                >
                  删除
                </button>
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* 自动采集监控 */}
      <div className="mb-4 rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
            ⏱️ 自动采集监控
            {scheduler && (
              <span className="ml-2 text-xs font-normal" style={{ color: '#86909C' }}>
                {scheduler.scheduled ? `每 ${Math.max(1, Math.round(scheduler.collectMs / 60000))} 分钟自动采集` : '定时任务已关闭'}
                {' · '}监控 {scheduler.watchCount} 个作品
                {' · '}自动分析 {scheduler.autoAnalyze ? `开（单轮≤${scheduler.autoAnalyzeLimit}条）` : '关'}
                {' · '}单作品最小间隔 {scheduler.minIntervalSeconds}s
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            {Object.keys(unread).length > 0 && (
              <span className="text-xs" style={{ color: '#86909C' }}>
                未读 评论/@{unread.mentions ?? 0} · 赞{unread.likes ?? 0} · 关注{unread.connections ?? 0}
              </span>
            )}
            <button
              onClick={() => void handleCollectNotifications()}
              disabled={notifBusy}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
            >
              {notifBusy ? '拉取中…' : '📥 拉取评论通知'}
            </button>
            <button
              onClick={() => void handleRunAll()}
              className="rounded-lg border px-3 py-1.5 text-xs font-medium"
              style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
            >
              ▶ 立即执行一轮
            </button>
          </div>
        </div>

        <div className="mb-3 flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>作品 ID</span>
            <input
              value={watchInput}
              onChange={(e) => setWatchInput(e.target.value)}
              placeholder="填入要长期监控的作品 ID"
              className="w-64 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>归属账号</span>
            <select
              value={selectedCredential}
              onChange={(e) => setSelectedCredential(e.target.value)}
              className="w-44 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            >
              <option value="">全局配置 / 默认账号</option>
              {credentials.map((c) => (
                <option key={c.credentialId} value={c.credentialId}>
                  {c.accountName}{c.defaultCredential ? '（默认）' : ''}{c.enabled ? '' : '（停用）'}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs" style={{ color: '#86909C' }}>
              xsec_token（自建桥必填，会过期）
            </span>
            <input
              value={watchXsecToken}
              onChange={(e) => setWatchXsecToken(e.target.value)}
              placeholder="形如 AB1cD…；用第三方数据服务可留空"
              className="w-72 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
          </div>
          <label className="flex items-center gap-2 pb-2 text-xs" style={{ color: '#4E5969' }}>
            <input
              type="checkbox"
              checked={watchAutoAnalyze}
              onChange={(e) => setWatchAutoAnalyze(e.target.checked)}
            />
            新评论自动 AI 分析
          </label>
          <button
            onClick={() => void handleAddWatch()}
            className="rounded-lg px-4 py-2 text-sm font-medium text-white"
            style={{ background: '#722ED1' }}
          >
            ＋ 加入监控
          </button>
        </div>

        {sourceStatus && (
          <div
            className="mb-3 rounded-lg px-3 py-2 text-xs"
            style={{
              background: sourceStatus.xiaohongshuConfigured ? '#E8FFEA' : '#FFF7E8',
              color: sourceStatus.xiaohongshuConfigured ? '#00782C' : '#9C5B00',
            }}
          >
            数据源：
            {sourceStatus.configuredSource === 'auto'
              ? '真实接口优先（不可用时回退模拟）'
              : sourceStatus.configuredSource === 'api'
                ? '仅真实接口（失败即报错）'
                : '仅模拟数据'}
            {sourceStatus.xiaohongshuConfigured
              ? ` · 小红书真实接口已配置（${sourceStatus.xiaohongshuAuthMode}）`
              : ` · 小红书真实接口未配置：${sourceStatus.hint}`}
          </div>
        )}

        {scheduler?.lastRun && (
          <div className="mb-3 text-xs" style={{ color: '#86909C' }}>
            上次运行：{timeStr(scheduler.lastRun.startedAt)}（{scheduler.lastRun.triggerType === 'schedule' ? '定时' : '手动'}）
            {' · '}作品 {scheduler.lastRun.worksScanned}
            {' · '}抓取 {scheduler.lastRun.commentsCollected}
            {' · '}新增 {scheduler.lastRun.commentsNew}
            {' · '}分析 {scheduler.lastRun.analyzed}
            {scheduler.lastRun.failed > 0 ? ` · 失败 ${scheduler.lastRun.failed}` : ''}
          </div>
        )}

        <div className="space-y-2">
          {watches.length === 0 && (
            <div className="text-xs" style={{ color: '#86909C' }}>
              还没有监控作品。把作品 ID 加入监控后，定时任务会持续抓取新评论并（可选）自动分析。
            </div>
          )}
          {watches.map((w) => (
            <div
              key={w.watchId}
              className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-xs"
              style={{ borderColor: '#F2F3F5' }}
            >
              <span className="font-medium" style={{ color: '#1D2129' }}>{w.workId}</span>
              <span className="rounded px-2 py-0.5" style={{ background: '#F2F3F5', color: '#4E5969' }}>{w.platform}</span>
              <span
                className="rounded px-2 py-0.5"
                style={{
                  background: w.enabled ? '#E8FFEA' : '#F2F3F5',
                  color: w.enabled ? '#00782C' : '#86909C',
                }}
              >
                {w.enabled ? '监控中' : '已暂停'}
              </span>
              {w.autoAnalyze && (
                <span className="rounded px-2 py-0.5" style={{ background: '#F5E8FF', color: '#722ED1' }}>
                  自动分析
                </span>
              )}
              {w.lastSource && (
                <span className="rounded px-2 py-0.5" style={{ background: '#F2F3F5', color: '#4E5969' }}>
                  上次源 {w.lastSource}
                </span>
              )}
              <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>
                账号 {credentialName(w.credentialId)}
              </span>
              {w.tokenState === 'EXPIRED' && (
                <span className="rounded px-2 py-0.5" style={{ background: '#FFECE8', color: '#F53F3F' }}>
                  ⚠️ 需更新票据（xsec_token 已失效{w.observedTtlDays ? `，观测有效期 ${w.observedTtlDays} 天` : ''}）
                </span>
              )}
              {w.tokenState !== 'EXPIRED' && w.tokenWarning === 'EXPIRING' && (
                <span className="rounded px-2 py-0.5" style={{ background: '#FFF7E8', color: '#9C5B00' }}>
                  ⏳ 票据即将过期（已用 {w.tokenAgeDays ?? '?'} 天，建议更新）
                </span>
              )}
              <span style={{ color: '#86909C' }}>
                累计 {w.totalCollected} 条 · 上次新增 {w.lastNewCount} ·{' '}
                {w.lastCollectedAt ? timeStr(w.lastCollectedAt) : '尚未采集'}
              </span>
              {w.lastError && <span style={{ color: '#F53F3F' }}>⚠️ {w.lastError}</span>}
              <span className="ml-auto flex gap-2">
                {(w.tokenState === 'EXPIRED' || w.tokenWarning === 'EXPIRING') && (
                  <>
                    <input
                      value={tokenEdits[w.watchId] ?? ''}
                      onChange={(e) => setTokenEdits((prev) => ({ ...prev, [w.watchId]: e.target.value }))}
                      placeholder="粘贴新的 xsec_token"
                      className="w-52 rounded-lg border px-2 py-1 outline-none"
                      style={{ borderColor: '#F53F3F' }}
                    />
                    <button
                      onClick={() => void handleUpdateWatchToken(w)}
                      className="rounded-lg border px-2 py-1"
                      style={{ borderColor: '#F53F3F', color: '#F53F3F' }}
                    >
                      更新票据
                    </button>
                  </>
                )}
                <button
                  onClick={() => void handleRunWatch(w.watchId)}
                  disabled={watchBusy === w.watchId}
                  className="rounded-lg border px-2 py-1 disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
                >
                  {watchBusy === w.watchId ? '采集中…' : '立即采集'}
                </button>
                <button
                  onClick={() => void handleToggleWatch(w)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#FF7D00' }}
                >
                  {w.enabled ? '暂停' : '启用'}
                </button>
                <button
                  onClick={() => void handleRemoveWatch(w.watchId)}
                  className="rounded-lg border px-2 py-1"
                  style={{ borderColor: '#E5E6EB', color: '#F53F3F' }}
                >
                  移除
                </button>
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* 统计区 */}
      <div className="mb-4 grid grid-cols-2 gap-4">
        <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
          <div className="mb-2 text-sm font-medium" style={{ color: '#1D2129' }}>意图分布</div>
          <div className="flex flex-wrap gap-2">
            {stats.intent.length === 0 && <span className="text-xs" style={{ color: '#86909C' }}>暂无数据，先采集评论</span>}
            {stats.intent.map((s) => (
              <span
                key={s.intent}
                className="rounded-full px-3 py-1 text-xs font-medium"
                style={{ background: '#F2F3F5', color: INTENT_COLORS[s.intent ?? ''] ?? '#4E5969' }}
              >
                {s.intent ?? '未识别'} · {s.cnt}
              </span>
            ))}
          </div>
        </div>
        <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
          <div className="mb-2 text-sm font-medium" style={{ color: '#1D2129' }}>情感分布</div>
          <div className="flex flex-wrap gap-2">
            {stats.sentiment.length === 0 && <span className="text-xs" style={{ color: '#86909C' }}>暂无数据，先采集评论</span>}
            {stats.sentiment.map((s) => (
              <span
                key={s.sentiment}
                className="rounded-full px-3 py-1 text-xs font-medium"
                style={{ background: '#F2F3F5', color: SENTIMENT_COLORS[s.sentiment ?? ''] ?? '#4E5969' }}
              >
                {s.sentiment ?? 'UNKNOWN'} · {s.cnt}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* 评论列表 */}
      <div className="rounded-xl border border-[#E5E6EB] bg-white p-4 shadow-sm">
        <div className="mb-3 flex items-center justify-between">
          <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
            评论列表（{comments.length}）
          </div>
        </div>
        {loading && <div className="py-8 text-center text-sm" style={{ color: '#86909C' }}>加载中…</div>}
        {!loading && comments.length === 0 && (
          <div className="py-8 text-center text-sm" style={{ color: '#86909C' }}>
            暂无评论。输入作品 ID 后点击「采集评论」开始。
          </div>
        )}
        <div className="space-y-3">
          {comments.map((c) => (
            <div key={c.commentId} className="rounded-xl border p-4" style={{ borderColor: '#E5E6EB' }}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium" style={{ color: '#1D2129' }}>{c.author || '匿名用户'}</span>
                {c.intent && (
                  <span className="rounded px-2 py-0.5 text-xs font-medium"
                    style={{ background: '#FFF0F5', color: INTENT_COLORS[c.intent] ?? '#C40E3A' }}>
                    {c.intent}
                  </span>
                )}
                {c.sentiment && (
                  <span className="rounded px-2 py-0.5 text-xs font-medium"
                    style={{ background: '#F2F3F5', color: SENTIMENT_COLORS[c.sentiment] ?? '#4E5969' }}>
                    {c.sentiment}
                  </span>
                )}
                <span className="rounded px-2 py-0.5 text-xs"
                  style={{ background: '#F2F3F5', color: '#4E5969' }}>
                  {STATUS_CN[c.replyStatus ?? 'NONE'] ?? c.replyStatus}
                </span>
                {c.collectedVia && (
                  <span className="rounded px-2 py-0.5 text-xs"
                    style={{ background: c.collectedVia === 'notification' ? '#F5E8FF' : '#F2F3F5',
                             color: c.collectedVia === 'notification' ? '#722ED1' : '#86909C' }}>
                    {c.collectedVia === 'notification' ? '通知采集' : c.collectedVia === 'mock' ? '模拟' : '笔记采集'}
                  </span>
                )}
                <span className="text-xs" style={{ color: '#86909C' }}>
                  👍 {c.likes ?? 0} · {timeStr(c.commentTime)}
                </span>
              </div>
              <div className="mt-2 text-sm leading-relaxed" style={{ color: '#1D2129' }}>{c.content}</div>
              {c.replyTo && (
                <div className="mt-1 text-xs" style={{ color: '#86909C' }}>回复 @{c.replyTo}</div>
              )}
              {c.aiSummary && (
                <div className="mt-2 text-xs" style={{ color: '#4E5969' }}>AI 摘要：{c.aiSummary}</div>
              )}
              {c.aiReply && (
                <div className="mt-1 rounded-lg px-3 py-2 text-xs" style={{ background: '#F7F8FA', color: '#4E5969' }}>
                  AI 回复：{c.aiReply}
                </div>
              )}
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  onClick={() => void handleAnalyzeOne(c.commentId)}
                  disabled={c.intent !== undefined && c.intent !== ''}
                  className="rounded-lg border px-3 py-1 text-xs font-medium disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
                >
                  ✨ 分析
                </button>
                <button
                  onClick={() => openChat(c)}
                  className="rounded-lg border px-3 py-1 text-xs font-medium"
                  style={{ borderColor: '#E5E6EB', color: '#722ED1' }}
                >
                  💬 AI 对话
                </button>
                <button
                  onClick={() => void handleApprove(c.commentId)}
                  disabled={c.replyStatus !== 'DRAFT'}
                  className="rounded-lg border px-3 py-1 text-xs font-medium disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#00B42A' }}
                >
                  ✅ 审核通过
                </button>
                <button
                  onClick={() => void handleSend(c.commentId)}
                  disabled={c.replyStatus !== 'APPROVED'}
                  className="rounded-lg border px-3 py-1 text-xs font-medium disabled:opacity-40"
                  style={{ borderColor: '#E5E6EB', color: '#FF2D5E' }}
                >
                  📤 发送回复
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* AI 对话面板 */}
      {chatComment && (
        <div className="mt-4 rounded-xl border bg-white p-4 shadow-sm" style={{ borderColor: '#E5E6EB' }}>
          <div className="mb-3 flex items-center justify-between">
            <div className="text-sm font-medium" style={{ color: '#1D2129' }}>
              💬 与「{chatComment.author || '匿名用户'}」对话
            </div>
            <button onClick={() => setChatComment(null)} className="text-xs" style={{ color: '#86909C' }}>
              关闭
            </button>
          </div>
          <div className="mb-3 rounded-lg px-3 py-2 text-xs" style={{ background: '#F7F8FA', color: '#4E5969' }}>
            原评论：{chatComment.content}
          </div>
          <div className="mb-3 max-h-64 space-y-2 overflow-y-auto rounded-lg border p-3" style={{ borderColor: '#F2F3F5' }}>
            {dialogTurns.length === 0 && (
              <div className="text-xs" style={{ color: '#86909C' }}>还没有对话，发送第一条消息让 AI 生成回复草稿</div>
            )}
            {dialogTurns.map((t, i) => (
              <div key={i} className={`flex ${t.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[75%] rounded-xl px-3 py-2 text-sm ${
                    t.role === 'user' ? 'text-white' : ''
                  }`}
                  style={
                    t.role === 'user'
                      ? { background: '#FF2D5E' }
                      : { background: '#F2F3F5', color: '#1D2129' }
                  }
                >
                  {t.content}
                </div>
              </div>
            ))}
            {chatBusy && (
              <div className="text-xs" style={{ color: '#86909C' }}>AI 思考中…</div>
            )}
          </div>
          <div className="mb-3 flex gap-2">
            <input
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void handleChatSend() }}
              placeholder="输入想和评论用户沟通的内容…"
              className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
            <button
              onClick={() => void handleChatSend()}
              disabled={chatBusy}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
              style={{ background: '#722ED1' }}
            >
              发送
            </button>
          </div>
          <div className="flex items-center gap-2">
            <input
              value={editReply}
              onChange={(e) => setEditReply(e.target.value)}
              placeholder="人工修改 AI 回复草稿…"
              className="flex-1 rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: '#E5E6EB' }}
            />
            <button
              onClick={() => void handleSaveReply()}
              className="rounded-lg border px-4 py-2 text-sm font-medium"
              style={{ borderColor: '#E5E6EB', color: '#165DFF' }}
            >
              保存草稿
            </button>
            <button
              onClick={() => void handleApprove(chatComment.commentId)}
              disabled={chatComment.replyStatus !== 'DRAFT'}
              className="rounded-lg border px-4 py-2 text-sm font-medium disabled:opacity-40"
              style={{ borderColor: '#E5E6EB', color: '#00B42A' }}
            >
              审核通过
            </button>
            <button
              onClick={() => void handleSend(chatComment.commentId)}
              disabled={chatComment.replyStatus !== 'APPROVED'}
              className="rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
              style={{ background: '#FF2D5E' }}
            >
              发送回复
            </button>
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
