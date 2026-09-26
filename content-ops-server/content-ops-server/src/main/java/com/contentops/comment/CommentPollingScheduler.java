package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 评论定时采集调度器：按 {@code contentops.comment.collect-ms} 间隔扫描监控作品并抓取新评论。
 *
 * <p>可用 {@code contentops.comment.scheduled=false} 关闭定时任务（仍支持手动采集）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentPollingScheduler {

    private final CommentCollectionJob collectionJob;
    private final CommentProperties properties;

    @Scheduled(fixedDelayString = "${contentops.comment.collect-ms:600000}",
            initialDelayString = "${contentops.comment.initial-delay-ms:30000}")
    public void pollComments() {
        if (!properties.isEnabled() || !properties.isScheduled()) {
            return;
        }
        try {
            CommentJobRun run = collectionJob.runOnce("schedule");
            if (run.getWorksScanned() > 0) {
                log.info("[Comment] 定时采集完成: 作品={}, 新增={}, 分析={}",
                        run.getWorksScanned(), run.getCommentsNew(), run.getAnalyzed());
            }
        } catch (Exception e) {
            log.error("[Comment] 定时采集异常: {}", e.getMessage(), e);
        }
    }
}