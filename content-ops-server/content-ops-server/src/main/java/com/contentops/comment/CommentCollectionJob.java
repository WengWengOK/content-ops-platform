package com.contentops.comment;

import com.contentops.common.credential.CredentialService;
import com.contentops.common.credential.PlatformCredential;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评论采集任务：扫描「监控作品」列表，周期性抓取新评论（可选自动 AI 分析）。
 *
 * <p>成本与风控约束：
 * <ul>
 *   <li>单轮最多处理 {@code max-works-per-tick} 个作品；</li>
 *   <li>同一作品两次采集至少间隔 {@code xiaohongshu.min-interval-seconds}；</li>
 *   <li>自动分析有 {@code auto-analyze-limit} 条数上限（超出部分保持未分析，可手动补跑）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentCollectionJob {

    private final CommentCollector collector;
    private final CommentRepository commentRepository;
    private final CommentWatchRepository watchRepository;
    private final CommentJobRunRepository jobRunRepository;
    private final CommentAnalysisService analysisService;
    private final CommentProperties properties;
    private final CredentialService credentialService;
    private final TokenWarningNotifier tokenWarningNotifier;

    /** 单个监控项的采集结果。 */
    public record WatchRunResult(String watchId, String workId, String source, String fallbackReason,
                                 int collected, int inserted, int analyzed, String error) {
    }

    /** 批量采集：扫描到期的监控项（定时任务与手动「全量采集」共用）。 */
    public CommentJobRun runOnce(String triggerType) {
        LocalDateTime startedAt = LocalDateTime.now();
        List<CommentWatch> watches = properties.isEnabled()
                ? watchRepository.listDue(properties.getXiaohongshu().getMinIntervalSeconds(),
                properties.getMaxWorksPerTick())
                : List.of();

        int scanned = 0, collected = 0, inserted = 0, analyzed = 0, failed = 0;
        int analyzeBudget = Math.max(0, properties.getAutoAnalyzeLimit());
        StringBuilder detail = new StringBuilder();

        // ① 通知增量：账号级拉取，最快发现新评论（接口未配置时自动跳过）
        if (properties.getXiaohongshu().isNotificationEnabled()) {
            List<PlatformCredential> credentials = credentialService.listEnabledDecrypted("xiaohongshu");
            AtomicInteger budget = new AtomicInteger(analyzeBudget);
            List<NotificationTaskResult> results = new ArrayList<>();

            if (credentials.isEmpty()) {
                results.add(collectNotificationsOnce(null, null, budget));
            } else {
                int parallelism = Math.max(1, Math.min(
                        properties.getXiaohongshu().getNotificationParallelism(), credentials.size()));
                ExecutorService pool = Executors.newFixedThreadPool(parallelism, runnable -> {
                    Thread thread = new Thread(runnable, "comment-notify");
                    thread.setDaemon(true);
                    return thread;
                });
                try {
                    List<Future<NotificationTaskResult>> futures = new ArrayList<>();
                    for (PlatformCredential credential : credentials) {
                        futures.add(pool.submit(() -> {
                            int delay = Math.max(0, properties.getXiaohongshu().getNotificationPerAccountDelayMs());
                            if (delay > 0) {
                                Thread.sleep(delay);
                            }
                            return collectNotificationsOnce(credential.getOwnerId(),
                                    credential.getCredentialId(), budget);
                        }));
                    }
                    for (Future<NotificationTaskResult> future : futures) {
                        try {
                            results.add(future.get(180, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            results.add(new NotificationTaskResult(null, null, 0, 0, 0, 1,
                                    "通知任务失败[" + e.getMessage() + "]; "));
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
            }

            for (NotificationTaskResult result : results) {
                collected += result.collected();
                inserted += result.inserted();
                analyzed += result.analyzed();
                failed += result.failed();
                if (result.detail() != null) {
                    detail.append(result.detail());
                }
            }
            analyzeBudget = budget.get();
        }

        // ② 粉丝标签：同步「新增关注」通知（真实信号，用于 FAN 标签）
        if (properties.getRelation().isEnabled() && properties.getRelation().isSyncFollowers()) {
            int markedFollowers = 0;
            List<PlatformCredential> credentials = credentialService.listEnabledDecrypted("xiaohongshu");
            if (credentials.isEmpty()) {
                markedFollowers = collector.syncFollowers(null, null);
            } else {
                for (PlatformCredential credential : credentials) {
                    markedFollowers += collector.syncFollowers(credential.getOwnerId(),
                            credential.getCredentialId());
                }
            }
            if (markedFollowers > 0) {
                detail.append("粉丝标签同步: ").append(markedFollowers).append(" 人; ");
            }
        }
        for (CommentWatch watch : watches) {
            scanned++;
            WatchRunResult result = runForWatch(watch, analyzeBudget);
            collected += result.collected();
            inserted += result.inserted();
            analyzed += result.analyzed();
            analyzeBudget -= result.analyzed();
            if (result.error() != null) {
                failed++;
            }
            if (detail.length() < 1500) {
                detail.append(result.workId()).append(':')
                        .append(result.source() == null ? "-" : result.source())
                        .append(" 抓取").append(result.collected())
                        .append(" 新增").append(result.inserted())
                        .append(" 分析").append(result.analyzed());
                if (result.error() != null) {
                    detail.append(" 错误[").append(result.error()).append(']');
                }
                detail.append("; ");
            }
        }


        CommentJobRun run = CommentJobRun.builder()
                .runId(UUID.randomUUID().toString())
                .triggerType(triggerType)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .worksScanned(scanned)
                .commentsCollected(collected)
                .commentsNew(inserted)
                .analyzed(analyzed)
                .failed(failed)
                .source(scanned == 0 ? "none" : "auto")
                .detail(detail.toString())
                .build();
        jobRunRepository.insert(run);
        log.info("[Comment] 采集任务完成: trigger={}, 作品={}, 抓取={}, 新增={}, 分析={}, 失败={}",
                triggerType, scanned, collected, inserted, analyzed, failed);

        // 票据预警通知（失败不影响采集结果）
        try {
            TokenWarningNotifier.NotifyResult notifyResult = tokenWarningNotifier.notifyIfNeeded(null, false);
            if (notifyResult.notified() > 0) {
                log.info("[Comment] 本轮已推送票据预警: 通知={}, 跳过={}",
                        notifyResult.notified(), notifyResult.skipped());
            }
        } catch (Exception e) {
            log.warn("[Comment] 票据预警推送异常（忽略）: {}", e.getMessage());
        }
        return run;
    }

    /** 采集单个监控项（供定时任务与「立即采集」按钮调用）。 */
    public WatchRunResult runForWatch(CommentWatch watch, int analyzeBudget) {
        int collected = 0, inserted = 0, analyzed = 0;
        String source = null, fallbackReason = null, error = null;
        List<Comment> fresh = new ArrayList<>();
        try {
            CommentCollector.CollectionResult result = collector.collect(watch.getPlatform(), watch.getWorkId(),
                    watch.getOwnerId(), watch.getXsecToken(), watch.getCredentialId());
            source = result.source();
            fallbackReason = result.fallbackReason();
            for (Comment c : result.commentsOrEmpty()) {
                collected++;
                if (commentRepository.exists(c.getCommentId())) {
                    continue;
                }
                commentRepository.insert(c);
                if (commentRepository.exists(c.getCommentId())) {
                    inserted++;
                    fresh.add(c);
                }
            }
            if (watch.isAutoAnalyze() && properties.isAutoAnalyze()) {
                analyzed = analyzeFresh(fresh, analyzeBudget);
            }
            watchRepository.updateAfterRun(watch.getWatchId(), inserted, inserted, source, null);
            if (CommentTokenHealth.isNoteTokenExpired(result.fallbackReason())) {
                watchRepository.markTokenExpired(watch.getWatchId(), result.fallbackReason());
            }
            log.info("[Comment] 监控项采集完成: watchId={}, workId={}, source={}, 新增={}, 分析={}",
                    watch.getWatchId(), watch.getWorkId(), source, inserted, analyzed);
        } catch (Exception e) {
            error = e.getMessage();
            watchRepository.updateAfterRun(watch.getWatchId(), 0, 0, source, error);
            if (CommentTokenHealth.isNoteTokenExpired(error)) {
                watchRepository.markTokenExpired(watch.getWatchId(), error);
            }
            log.warn("[Comment] 监控项采集失败: watchId={}, workId={}, err={}",
                    watch.getWatchId(), watch.getWorkId(), e.getMessage());
        }
        return new WatchRunResult(watch.getWatchId(), watch.getWorkId(), source, fallbackReason,
                collected, inserted, analyzed, error);
    }

    /**
     * 拉一次通知增量（某个账号）并入库/分析。
     *
     * @return [抓取数, 新增数, 分析数, 失败数]
     */
    /** 单个账号的通知增量结果（并行安全：只返回数据，不直接写共享 StringBuilder）。 */
    private record NotificationTaskResult(String ownerId, String credentialId, int collected, int inserted,
                                          int analyzed, int failed, String detail) {
    }

    private NotificationTaskResult collectNotificationsOnce(String ownerId, String credentialId,
                                                            AtomicInteger analyzeBudget) {
        try {
            CommentCollector.NotificationCollectionResult notif = collector.collectFromNotifications(
                    ownerId, null, properties.getXiaohongshu().getNotificationLimit(), credentialId);
            if (!"api".equals(notif.source())) {
                return new NotificationTaskResult(ownerId, credentialId, 0, 0, 0, 0, null);
            }
            int notifCollected = 0;
            int notifInserted = 0;
            List<Comment> fresh = new ArrayList<>();
            for (Comment c : notif.commentsOrEmpty()) {
                notifCollected++;
                if (commentRepository.exists(c.getCommentId())) {
                    continue;
                }
                commentRepository.insert(c);
                if (commentRepository.exists(c.getCommentId())) {
                    notifInserted++;
                    fresh.add(c);
                }
            }
            int notifAnalyzed = analyzeFreshAtomic(fresh, analyzeBudget);
            String line = "通知[" + notif.tab() + (ownerId == null ? "" : "@" + ownerId) + "]: 抓取"
                    + notifCollected + " 新增" + notifInserted + " 分析" + notifAnalyzed
                    + " 新监控" + notif.createdWatches() + " filtered=" + notif.filtered() + "; ";
            log.info("[Comment] 通知增量: owner={}, credential={}, tab={}, 新增={}, 分析={}, 新监控={}",
                    ownerId, credentialId, notif.tab(), notifInserted, notifAnalyzed, notif.createdWatches());
            return new NotificationTaskResult(ownerId, credentialId, notifCollected, notifInserted,
                    notifAnalyzed, 0, line);
        } catch (Exception e) {
            String message = e.getMessage();
            if (credentialId != null && CommentTokenHealth.isAccountTokenInvalid(message)) {
                credentialService.markTokenState(credentialId, "AUTH_INVALID", message);
            }
            log.warn("[Comment] 通知增量采集失败: owner={}, credential={}, err={}",
                    ownerId, credentialId, message);
            return new NotificationTaskResult(ownerId, credentialId, 0, 0, 0, 1,
                    "通知采集失败[" + message + "]; ");
        }
    }

    /** 并行安全的分析预算消费：从共享预算里抢占名额，失败归还。 */
    private int analyzeFreshAtomic(List<Comment> fresh, AtomicInteger budget) {
        if (fresh.isEmpty()) {
            return 0;
        }
        int analyzed = 0;
        for (Comment comment : fresh) {
            int remaining = budget.get();
            if (remaining <= 0) {
                break;
            }
            if (!budget.compareAndSet(remaining, remaining - 1)) {
                continue;
            }
            try {
                Comment analyzedComment = analysisService.analyze(comment);
                commentRepository.updateAnalysisAndStatus(comment.getCommentId(),
                        analyzedComment.getIntent(), analyzedComment.getSentiment(),
                        analyzedComment.getAiSummary(), analyzedComment.getAiReply(), analyzedComment.getIntentSource(), analyzedComment.getIntentScore());
                analyzed++;
            } catch (Exception e) {
                budget.incrementAndGet();
                log.warn("[Comment] 自动分析失败: commentId={}, err={}",
                        comment.getCommentId(), e.getMessage());
            }
        }
        return analyzed;
    }

    /** 对新入库评论做 AI 分析（受预算上限约束，单条失败不影响其它）。 */
    private int analyzeFresh(List<Comment> fresh, int budget) {
        if (fresh.isEmpty() || budget <= 0) {
            return 0;
        }
        int analyzed = 0;
        for (Comment comment : fresh) {
            if (analyzed >= budget) {
                break;
            }
            try {
                Comment analyzedComment = analysisService.analyze(comment);
                commentRepository.updateAnalysisAndStatus(comment.getCommentId(),
                        analyzedComment.getIntent(), analyzedComment.getSentiment(),
                        analyzedComment.getAiSummary(), analyzedComment.getAiReply(), analyzedComment.getIntentSource(), analyzedComment.getIntentScore());
                analyzed++;
            } catch (Exception e) {
                log.warn("[Comment] 自动分析失败: commentId={}, err={}",
                        comment.getCommentId(), e.getMessage());
            }
        }
        return analyzed;
    }
}