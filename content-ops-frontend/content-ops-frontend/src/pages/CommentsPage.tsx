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
  notifyTokenWarnings,
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

import { AppToast } from '@/components/comments/AppToast'
import { CommentChatPanel } from '@/components/comments/CommentChatPanel'
import { CommentListPanel } from '@/components/comments/CommentListPanel'
import { CommentStatsCards } from '@/components/comments/CommentStatsCards'
import { CommentsToolbar } from '@/components/comments/CommentsToolbar'
import { CredentialManagerCard } from '@/components/comments/CredentialManagerCard'
import { OpsOverviewCard } from '@/components/comments/OpsOverviewCard'
import { WatchMonitorCard } from '@/components/comments/WatchMonitorCard'
import { type DialogTurn } from '@/components/comments/constants'

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
  const [notifyBusy, setNotifyBusy] = useState(false)

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

  const handleNotifyTokenWarnings = async () => {
    setNotifyBusy(true)
    try {
      const res = await notifyTokenWarnings(true)
      const channels = Object.entries(res.channels || {})
        .map(([name, value]: [string, any]) => `${name}:${value?.success ? '成功' : value?.configured === false ? '未配置' : '失败'}`)
        .join(' / ')
      showToast(
        res.notified > 0
          ? `预警已推送：${res.notified} 条（${channels}）`
          : `没有需要推送的预警（候选 ${res.candidates} 条，已去重 ${res.skipped} 条）`,
        res.notified > 0 ? '#00B42A' : '#165DFF'
      )
      await loadOps()
    } catch (err: any) {
      showToast(err?.message || '推送失败', '#F53F3F')
    } finally {
      setNotifyBusy(false)
    }
  }

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

  const [activeTab, setActiveTabState] = useState<TabKey>(initialTab())
  /** 切换模块并把当前模块写进 URL，方便深链（如飞书预警按钮直达运维总览） */
  const setActiveTab = (key: TabKey) => {
    setActiveTabState(key)
    window.history.replaceState(null, '', `${window.location.pathname}?tab=${key}`)
  }

  const pendingTokenIssues =
    (ops?.watchHealth.expired ?? 0) + (ops?.watchHealth.expiringSoon ?? 0)
  const abnormalAccounts =
    ops?.credentials.filter((c) => c.tokenState === 'AUTH_INVALID' || c.tokenState === 'ERROR').length ?? 0
  const pendingRotation = ops?.rotation.pendingRotation ?? 0

  const tabs: { key: TabKey; label: string; badge?: number }[] = [
    { key: 'workspace', label: '评论工作台' },
    { key: 'automation', label: '自动采集', badge: watches.length || undefined },
    { key: 'accounts', label: '账号凭据', badge: credentials.length || undefined },
    {
      key: 'ops',
      label: '运维总览',
      badge: pendingTokenIssues + abnormalAccounts + pendingRotation || undefined,
    },
  ]

  return (
    <Layout pageTitle="评论区 AI 助手" breadcrumbs={[{ label: '评论区 AI 助手' }]} activeNav="comments">
      {/* 顶部状态条：不进子页面也能看到关键健康度 */}
      <div className="mb-4 flex flex-wrap items-center gap-4 rounded-xl border border-[#E5E6EB] bg-white px-4 py-3 shadow-sm">
        <StatusPill label="账号" value={credentials.length} tone="ok" />
        <StatusPill label="监控作品" value={watches.length} tone="ok" />
        <StatusPill label="票据待处理" value={pendingTokenIssues} tone={pendingTokenIssues > 0 ? 'warn' : 'ok'} />
        <StatusPill label="账号异常" value={abnormalAccounts} tone={abnormalAccounts > 0 ? 'danger' : 'ok'} />
        <StatusPill label="待轮换密钥" value={pendingRotation} tone={pendingRotation > 0 ? 'warn' : 'ok'} />
        {scheduler?.lastRun?.startedAt && (
          <span className="ml-auto text-xs" style={{ color: '#86909C' }}>
            最近采集：{timeStr(scheduler.lastRun.startedAt)}
          </span>
        )}
      </div>

      {/* 模块切换（分段控件） */}
      <div className="mb-4 inline-flex flex-wrap gap-1 rounded-xl border border-[#E5E6EB] bg-white p-1 shadow-sm">
        {tabs.map((tab) => {
          const active = activeTab === tab.key
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className="rounded-lg px-4 py-2 text-sm font-medium transition-colors"
              style={
                active
                  ? { background: '#FF2D5E', color: '#fff' }
                  : { background: 'transparent', color: '#4E5969' }
              }
            >
              {tab.label}
              {tab.badge ? (
                <span
                  className="ml-2 rounded-full px-1.5 py-0.5 text-[10px]"
                  style={active ? { background: 'rgba(255,255,255,0.25)' } : { background: '#F2F3F5' }}
                >
                  {tab.badge}
                </span>
              ) : null}
            </button>
          )
        })}
      </div>

      {activeTab === 'workspace' && (
        <div className="space-y-4">
          <CommentsToolbar
            platform={platform}
            setPlatform={setPlatform}
            workId={workId}
            setWorkId={setWorkId}
            intent={intent}
            setIntent={setIntent}
            sentiment={sentiment}
            setSentiment={setSentiment}
            error={error}
            handleCollect={handleCollect}
            handleAnalyzeAll={handleAnalyzeAll}
            loadComments={loadComments}
            loadStats={loadStats}
          />
          <CommentStatsCards stats={stats} intent={intent} sentiment={sentiment} />
          <CommentListPanel
            comments={comments}
            loading={loading}
            handleAnalyzeOne={handleAnalyzeOne}
            handleApprove={handleApprove}
            handleSend={handleSend}
            openChat={openChat}
            timeStr={timeStr}
          />
          <CommentChatPanel
            chatComment={chatComment}
            setChatComment={setChatComment}
            chatInput={chatInput}
            setChatInput={setChatInput}
            chatBusy={chatBusy}
            dialogTurns={dialogTurns}
            editReply={editReply}
            setEditReply={setEditReply}
            handleChatSend={handleChatSend}
            handleSaveReply={handleSaveReply}
            handleApprove={handleApprove}
            handleSend={handleSend}
          />
        </div>
      )}

      {activeTab === 'automation' && (
        <WatchMonitorCard
          platform={platform}
          watches={watches}
          scheduler={scheduler}
          sourceStatus={sourceStatus}
          credentials={credentials}
          selectedCredential={selectedCredential}
          setSelectedCredential={setSelectedCredential}
          watchInput={watchInput}
          setWatchInput={setWatchInput}
          watchXsecToken={watchXsecToken}
          setWatchXsecToken={setWatchXsecToken}
          watchAutoAnalyze={watchAutoAnalyze}
          setWatchAutoAnalyze={setWatchAutoAnalyze}
          watchBusy={watchBusy}
          unread={unread}
          notifBusy={notifBusy}
          tokenEdits={tokenEdits}
          setTokenEdits={setTokenEdits}
          handleAddWatch={handleAddWatch}
          handleUpdateWatchToken={handleUpdateWatchToken}
          handleToggleWatch={handleToggleWatch}
          handleRemoveWatch={handleRemoveWatch}
          handleRunWatch={handleRunWatch}
          handleCollectNotifications={handleCollectNotifications}
          handleRunAll={handleRunAll}
          timeStr={timeStr}
          credentialName={credentialName}
        />
      )}

      {activeTab === 'accounts' && (
        <CredentialManagerCard
          credentials={credentials}
          credName={credName}
          setCredName={setCredName}
          credBaseUrl={credBaseUrl}
          setCredBaseUrl={setCredBaseUrl}
          credToken={credToken}
          setCredToken={setCredToken}
          credDefault={credDefault}
          setCredDefault={setCredDefault}
          credBusy={credBusy}
          probes={probes}
          handleCreateCredential={handleCreateCredential}
          handleProbeCredential={handleProbeCredential}
          handleSetDefaultCredential={handleSetDefaultCredential}
          handleToggleCredential={handleToggleCredential}
          handleDeleteCredential={handleDeleteCredential}
        />
      )}

      {activeTab === 'ops' && (
        <OpsOverviewCard
          ops={ops}
          opsBusy={opsBusy}
          notifyBusy={notifyBusy}
          credentials={credentials}
          handleRotateKeys={handleRotateKeys}
          handleNotifyTokenWarnings={handleNotifyTokenWarnings}
          loadOps={loadOps}
        />
      )}

      <AppToast toast={toast} />
    </Layout>
  )
}

type TabKey = 'workspace' | 'automation' | 'accounts' | 'ops'

const TAB_KEYS: TabKey[] = ['workspace', 'automation', 'accounts', 'ops']

/** 从 URL 读取初始模块（?tab=ops），非法值回落到评论工作台。 */
function initialTab(): TabKey {
  const value = new URLSearchParams(window.location.search).get('tab') ?? ''
  return (TAB_KEYS as string[]).includes(value) ? (value as TabKey) : 'workspace'
}

/** 顶部状态胶囊：数值 + 语义色。 */
function StatusPill({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone: 'ok' | 'warn' | 'danger'
}) {
  const color = tone === 'danger' ? '#F53F3F' : tone === 'warn' ? '#9C5B00' : '#4E5969'
  return (
    <span className="flex items-center gap-2 text-xs" style={{ color: '#86909C' }}>
      <span>{label}</span>
      <span className="rounded-md px-2 py-0.5 font-medium" style={{ background: '#F7F8FA', color }}>
        {value}
      </span>
    </span>
  )
}