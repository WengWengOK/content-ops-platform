package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        return run;
    }

    /** 采集单个监控项（供定时任务与「立即采集」按钮调用）。 */
    public WatchRunResult runForWatch(CommentWatch watch, int analyzeBudget) {
        int collected = 0, inserted = 0, analyzed = 0;
        String source = null, fallbackReason = null, error = null;
        List<Comment> fresh = new ArrayList<>();
        try {
            CommentCollector.CollectionResult result =
                    collector.collect(watch.getPlatform(), watch.getWorkId(), watch.getOwnerId());
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
            log.info("[Comment] 监控项采集完成: watchId={}, workId={}, source={}, 新增={}, 分析={}",
                    watch.getWatchId(), watch.getWorkId(), source, inserted, analyzed);
        } catch (Exception e) {
            error = e.getMessage();
            watchRepository.updateAfterRun(watch.getWatchId(), 0, 0, source, error);
            log.warn("[Comment] 监控项采集失败: watchId={}, workId={}, err={}",
                    watch.getWatchId(), watch.getWorkId(), e.getMessage());
        }
        return new WatchRunResult(watch.getWatchId(), watch.getWorkId(), source, fallbackReason,
                collected, inserted, analyzed, error);
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
                        analyzedComment.getAiSummary(), analyzedComment.getAiReply());
                analyzed++;
            } catch (Exception e) {
                log.warn("[Comment] 自动分析失败: commentId={}, err={}",
                        comment.getCommentId(), e.getMessage());
            }
        }
        return analyzed;
    }
}