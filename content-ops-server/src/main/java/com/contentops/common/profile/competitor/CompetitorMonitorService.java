package com.contentops.common.profile.competitor;

import com.contentops.common.profile.competitor.CompetitorProfile.BasicProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.ContentProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.PerformanceProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 竞品定向监控服务（P0 定向竞品监控）。
 *
 * <p>在「通用搜索」基础上升级为「定向竞品监控」：为指定竞品建立定时监控任务，
 * 周期性拉取最新数据、更新画像并检测显著变化（新增爆款、风格转变、粉丝异动等），
 * 将变化历史沉淀以便运营及时响应。
 *
 * <p><b>存储：</b>监控任务与变化历史存储在内存中（{@link ConcurrentHashMap}），
 * 每个任务维护自身的执行时间与变化历史列表，支持并发安全读写。
 *
 * <p><b>变化检测维度：</b>
 * <ul>
 *   <li>粉丝增长率突变（超过配置阈值）</li>
 *   <li>新爆款出现（TOP 作品列表新增高互动作品）</li>
 *   <li>发文频率变化（相对变化超过配置阈值）</li>
 *   <li>内容方向转变（选题关键词集合变化占比超过配置阈值）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitorMonitorService {

    private final CompetitorProfileService profileService;
    private final CompetitorProfileProperties properties;

    /** 监控任务存储（monitorId -> MonitorTask），并发安全 */
    private final ConcurrentHashMap<String, MonitorTask> monitorTasks = new ConcurrentHashMap<>();

    /** 竞品账号 ID -> 监控任务 ID 的反向索引，便于按竞品查找监控 */
    private final ConcurrentHashMap<String, String> competitorToMonitor = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════
    // 核心方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 添加竞品监控任务 —— 同时构建初始画像并注册到画像服务。
     *
     * <p>若该竞品已存在监控任务，则直接返回已有任务（幂等）。
     *
     * @param competitorAccountId 竞品账号 ID
     * @param platform            平台标识
     * @param niche               所属领域
     * @param monitorFrequency    监控频率（小时），<=0 时使用配置默认值
     * @return 新建或已存在的监控任务
     */
    public MonitorTask addMonitor(String competitorAccountId, String platform, String niche, int monitorFrequency) {
        String existingId = competitorToMonitor.get(competitorAccountId);
        if (existingId != null) {
            MonitorTask existing = monitorTasks.get(existingId);
            if (existing != null) {
                log.info("Monitor already exists for competitor {}, reusing monitorId={}",
                        competitorAccountId, existingId);
                return existing;
            }
        }

        int frequency = monitorFrequency > 0 ? monitorFrequency : properties.getDefaultMonitorFrequencyHours();
        String monitorId = generateMonitorId(competitorAccountId);
        Instant now = Instant.now();

        // 构建初始画像并注册
        CompetitorProfile initialProfile = profileService.buildProfile(competitorAccountId, platform, niche);

        MonitorTask task = new MonitorTask(
                monitorId, competitorAccountId, platform, niche, frequency,
                now, now, initialProfile, new CopyOnWriteArrayList<>());
        monitorTasks.put(monitorId, task);
        competitorToMonitor.put(competitorAccountId, monitorId);

        log.info("Monitor added: monitorId={}, competitor={}, platform={}, niche={}, frequency={}h",
                monitorId, competitorAccountId, platform, niche, frequency);
        return task;
    }

    /**
     * 移除监控任务 —— 同时从画像服务移除竞品。
     *
     * @param competitorAccountId 竞品账号 ID
     * @return 被移除的监控任务；不存在时返回 null
     */
    public MonitorTask removeMonitor(String competitorAccountId) {
        String monitorId = competitorToMonitor.remove(competitorAccountId);
        if (monitorId == null) {
            return null;
        }
        MonitorTask removed = monitorTasks.remove(monitorId);
        if (removed != null) {
            profileService.removeCompetitor(competitorAccountId);
            log.info("Monitor removed: monitorId={}, competitor={}", monitorId, competitorAccountId);
        }
        return removed;
    }

    /**
     * 列出所有监控任务。
     *
     * @return 监控任务列表
     */
    public List<MonitorTask> listMonitors() {
        return List.copyOf(monitorTasks.values());
    }

    /**
     * 执行单次监控任务 —— 拉取最新数据、更新画像并检测变化。
     *
     * <p>流程：
     * <ol>
     *   <li>校验任务存在性与执行频率（未到频率间隔则跳过，除非强制）</li>
     *   <li>记录旧画像，调用 {@link CompetitorProfileService#updateProfile} 拉取新画像</li>
     *   <li>调用 {@link #detectChanges} 对比新旧画像，生成变化列表</li>
     *   <li>将变化追加到任务历史，更新上次执行时间</li>
     * </ol>
     *
     * @param monitorId 监控任务 ID
     * @return 监控执行结果（含新画像与本次检测到的变化）；任务不存在返回 null
     */
    public MonitorResult executeMonitorTask(String monitorId) {
        MonitorTask task = monitorTasks.get(monitorId);
        if (task == null) {
            log.warn("Monitor task not found: {}", monitorId);
            return null;
        }

        // 频率节流：未到执行间隔则跳过实际拉取
        Instant now = Instant.now();
        if (!isDue(task, now)) {
            log.debug("Monitor {} not due yet (last={}, frequency={}h), skipping", monitorId,
                    task.lastExecutedAt(), task.monitorFrequencyHours());
            return new MonitorResult(task, task.lastProfile(), List.of(), false, "未到监控频率，跳过本次执行");
        }

        CompetitorProfile oldProfile = task.lastProfile();
        CompetitorProfile newProfile = profileService.updateProfile(task.competitorAccountId());
        if (newProfile == null) {
            log.warn("Profile update returned null for monitor {}, competitor may have been removed", monitorId);
            return new MonitorResult(task, oldProfile, List.of(), false, "竞品画像更新失败（可能已移除）");
        }

        List<ProfileChange> changes = detectChanges(oldProfile, newProfile);
        task.changeHistory().addAll(changes);
        // 历史记录上限保护，避免无限增长
        if (task.changeHistory().size() > 200) {
            task.changeHistory().subList(0, task.changeHistory().size() - 200).clear();
        }

        // 更新任务状态（重建不可变 record）
        MonitorTask updated = new MonitorTask(
                task.monitorId(), task.competitorAccountId(), task.platform(), task.niche(),
                task.monitorFrequencyHours(), task.createdAt(), now, newProfile, task.changeHistory());
        monitorTasks.put(monitorId, updated);

        log.info("Monitor executed: monitorId={}, competitor={}, changes={}",
                monitorId, task.competitorAccountId(), changes.size());
        return new MonitorResult(updated, newProfile, changes, true,
                changes.isEmpty() ? "无显著变化" : "检测到 " + changes.size() + " 项变化");
    }

    /**
     * 检测画像变化 —— 对比新旧画像，识别粉丝异动、新爆款、发文频率变化、内容方向转变。
     *
     * <p>各维度阈值由 {@link CompetitorProfileProperties} 配置驱动，支持灵敏度热更新。
     *
     * @param oldProfile 旧画像（可为 null，表示首次监控）
     * @param newProfile 新画像
     * @return 变化列表（空列表表示无显著变化）
     */
    public List<ProfileChange> detectChanges(CompetitorProfile oldProfile, CompetitorProfile newProfile) {
        List<ProfileChange> changes = new ArrayList<>();
        if (newProfile == null) {
            return changes;
        }
        if (oldProfile == null) {
            changes.add(new ProfileChange(ChangeType.INITIAL_PROFILE,
                    "首次建立竞品画像", Severity.INFO, Instant.now(), null, null));
            return changes;
        }

        detectFollowerAnomaly(oldProfile, newProfile, changes);
        detectNewHitWorks(oldProfile, newProfile, changes);
        detectPostingFrequencyChange(oldProfile, newProfile, changes);
        detectTopicDirectionShift(oldProfile, newProfile, changes);
        detectGrowthTrendShift(oldProfile, newProfile, changes);

        return changes;
    }

    /**
     * 获取指定竞品的监控任务。
     *
     * @param competitorAccountId 竞品账号 ID
     * @return 监控任务；不存在返回 null
     */
    public MonitorTask getMonitor(String competitorAccountId) {
        String monitorId = competitorToMonitor.get(competitorAccountId);
        return monitorId != null ? monitorTasks.get(monitorId) : null;
    }

    /**
     * 获取指定竞品的完整变化历史。
     *
     * @param competitorAccountId 竞品账号 ID
     * @return 变化历史列表；不存在返回空列表
     */
    public List<ProfileChange> getChangeHistory(String competitorAccountId) {
        MonitorTask task = getMonitor(competitorAccountId);
        return task != null ? List.copyOf(task.changeHistory()) : List.of();
    }

    // ════════════════════════════════════════════════════════════════
    // 变化检测子方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测粉丝增长率突变。
     */
    private void detectFollowerAnomaly(CompetitorProfile oldP, CompetitorProfile newP, List<ProfileChange> changes) {
        BasicProfile oldBasic = oldP.basic();
        BasicProfile newBasic = newP.basic();
        if (oldBasic == null || newBasic == null) {
            return;
        }
        double delta = newBasic.growthRate30d() - oldBasic.growthRate30d();
        double threshold = properties.getFollowerGrowthSpikeThreshold();
        if (Math.abs(delta) > threshold) {
            changes.add(new ProfileChange(
                    ChangeType.FOLLOWER_ANOMALY,
                    String.format("粉丝增长率突变：%.1f%% → %.1f%%（变化 %.1f%%）",
                            oldBasic.growthRate30d() * 100, newBasic.growthRate30d() * 100, delta * 100),
                    delta > 0 ? Severity.POSITIVE : Severity.WARNING,
                    Instant.now(),
                    String.format("%.4f", oldBasic.growthRate30d()),
                    String.format("%.4f", newBasic.growthRate30d())));
        }
    }

    /**
     * 检测新爆款出现 —— 新 TOP 作品列表中存在旧列表没有的高互动作品。
     */
    private void detectNewHitWorks(CompetitorProfile oldP, CompetitorProfile newP, List<ProfileChange> changes) {
        ContentProfile oldContent = oldP.content();
        ContentProfile newContent = newP.content();
        if (oldContent == null || newContent == null) {
            return;
        }
        List<String> oldTitles = oldContent.topWorks().stream()
                .map(CompetitorProfile.TopWork::title)
                .toList();
        List<CompetitorProfile.TopWork> newHits = newContent.topWorks().stream()
                .filter(w -> !oldTitles.contains(w.title()))
                .filter(w -> w.engagementRate() >= properties.getHitRateThreshold())
                .toList();
        if (!newHits.isEmpty()) {
            String titles = newHits.stream()
                    .map(CompetitorProfile.TopWork::title)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            changes.add(new ProfileChange(
                    ChangeType.NEW_HIT,
                    String.format("新增 %d 篇爆款：%s", newHits.size(), titles),
                    Severity.POSITIVE, Instant.now(),
                    String.valueOf(oldContent.topWorks().size()),
                    String.valueOf(newContent.topWorks().size())));
        }
    }

    /**
     * 检测发文频率变化。
     */
    private void detectPostingFrequencyChange(CompetitorProfile oldP, CompetitorProfile newP, List<ProfileChange> changes) {
        BasicProfile oldBasic = oldP.basic();
        BasicProfile newBasic = newP.basic();
        if (oldBasic == null || newBasic == null || oldBasic.postingFrequencyPerWeek() <= 0) {
            return;
        }
        double relativeChange = (newBasic.postingFrequencyPerWeek() - oldBasic.postingFrequencyPerWeek())
                / oldBasic.postingFrequencyPerWeek();
        double threshold = properties.getPostingFrequencyChangeThreshold();
        if (Math.abs(relativeChange) > threshold) {
            changes.add(new ProfileChange(
                    ChangeType.POSTING_FREQUENCY_CHANGE,
                    String.format("发文频率变化：%.1f → %.1f 篇/周（%+.0f%%）",
                            oldBasic.postingFrequencyPerWeek(), newBasic.postingFrequencyPerWeek(),
                            relativeChange * 100),
                    relativeChange > 0 ? Severity.POSITIVE : Severity.WARNING,
                    Instant.now(),
                    String.format("%.2f", oldBasic.postingFrequencyPerWeek()),
                    String.format("%.2f", newBasic.postingFrequencyPerWeek())));
        }
    }

    /**
     * 检测内容方向转变 —— 选题关键词集合变化占比。
     */
    private void detectTopicDirectionShift(CompetitorProfile oldP, CompetitorProfile newP, List<ProfileChange> changes) {
        ContentProfile oldContent = oldP.content();
        ContentProfile newContent = newP.content();
        if (oldContent == null || newContent == null) {
            return;
        }
        List<String> oldTopics = oldContent.topicKeywords();
        List<String> newTopics = newContent.topicKeywords();
        if (oldTopics.isEmpty() && newTopics.isEmpty()) {
            return;
        }
        double changeRatio = computeTopicChangeRatio(oldTopics, newTopics);
        double threshold = properties.getTopicDirectionChangeThreshold();
        if (changeRatio > threshold) {
            changes.add(new ProfileChange(
                    ChangeType.TOPIC_DIRECTION_SHIFT,
                    String.format("内容方向转变：选题关键词变化占比 %.0f%%", changeRatio * 100),
                    Severity.WARNING, Instant.now(),
                    String.join(",", oldTopics), String.join(",", newTopics)));
        }
    }

    /**
     * 检测增长趋势切换（上升 ↔ 稳定 ↔ 下降）。
     */
    private void detectGrowthTrendShift(CompetitorProfile oldP, CompetitorProfile newP, List<ProfileChange> changes) {
        PerformanceProfile oldPerf = oldP.performance();
        PerformanceProfile newPerf = newP.performance();
        if (oldPerf == null || newPerf == null) {
            return;
        }
        if (oldPerf.growthTrend() != newPerf.growthTrend()) {
            changes.add(new ProfileChange(
                    ChangeType.GROWTH_TREND_SHIFT,
                    String.format("增长趋势转变：%s → %s",
                            oldPerf.growthTrend().label(), newPerf.growthTrend().label()),
                    newPerf.growthTrend() == CompetitorProfile.GrowthTrend.ASCENDING
                            ? Severity.POSITIVE : Severity.WARNING,
                    Instant.now(),
                    oldPerf.growthTrend().name(), newPerf.growthTrend().name()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 判断监控任务是否到执行时间。
     */
    private boolean isDue(MonitorTask task, Instant now) {
        Duration elapsed = Duration.between(task.lastExecutedAt(), now);
        return elapsed.toHours() >= task.monitorFrequencyHours();
    }

    /**
     * 计算选题关键词集合的变化占比（不对称差异率）。
     *
     * @param oldTopics 旧关键词
     * @param newTopics 新关键词
     * @return 变化占比（0.0-1.0）
     */
    private double computeTopicChangeRatio(List<String> oldTopics, List<String> newTopics) {
        Set<String> oldSet = new HashSet<>(oldTopics);
        Set<String> newSet = new HashSet<>(newTopics);
        Set<String> union = new HashSet<>(oldSet);
        union.addAll(newSet);
        if (union.isEmpty()) {
            return 0.0;
        }
        // 对称差异率 = (并集 - 交集) / 并集
        Set<String> intersection = new HashSet<>(oldSet);
        intersection.retainAll(newSet);
        int symmetricDiff = union.size() - intersection.size();
        return (double) symmetricDiff / union.size();
    }

    /**
     * 生成监控任务 ID。
     */
    private String generateMonitorId(String competitorAccountId) {
        return "mon_" + competitorAccountId + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ════════════════════════════════════════════════════════════════
    // 数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 监控任务 —— 描述对单个竞品的定向监控配置与状态。
     *
     * @param monitorId           监控任务唯一 ID
     * @param competitorAccountId 竞品账号 ID
     * @param platform            平台
     * @param niche               领域
     * @param monitorFrequencyHours 监控频率（小时）
     * @param createdAt           任务创建时间
     * @param lastExecutedAt      上次执行时间
     * @param lastProfile         最近一次拉取的画像
     * @param changeHistory       变化历史（线程安全列表）
     */
    public record MonitorTask(
            String monitorId,
            String competitorAccountId,
            String platform,
            String niche,
            int monitorFrequencyHours,
            Instant createdAt,
            Instant lastExecutedAt,
            CompetitorProfile lastProfile,
            CopyOnWriteArrayList<ProfileChange> changeHistory
    ) {
    }

    /**
     * 监控执行结果。
     *
     * @param task        执行后的监控任务
     * @param profile     本次拉取的画像
     * @param changes     本次检测到的变化
     * @param executed    是否真正执行了拉取（false 表示因频率未到而跳过）
     * @param message     结果描述
     */
    public record MonitorResult(
            MonitorTask task,
            CompetitorProfile profile,
            List<ProfileChange> changes,
            boolean executed,
            String message
    ) {
    }

    /**
     * 画像变化记录 —— 单次监控检测到的一项显著变化。
     *
     * @param changeType 变化类型
     * @param description 变化描述
     * @param severity    严重程度 / 趋向
     * @param detectedAt  检测时间
     * @param beforeValue 变化前的值（字符串表示）
     * @param afterValue  变化后的值（字符串表示）
     */
    public record ProfileChange(
            ChangeType changeType,
            String description,
            Severity severity,
            Instant detectedAt,
            String beforeValue,
            String afterValue
    ) {
    }

    /** 变化类型枚举 */
    public enum ChangeType {
        /** 首次建立画像 */
        INITIAL_PROFILE("首次画像"),
        /** 粉丝增长率突变 */
        FOLLOWER_ANOMALY("粉丝异动"),
        /** 新爆款出现 */
        NEW_HIT("新增爆款"),
        /** 发文频率变化 */
        POSTING_FREQUENCY_CHANGE("发文频率变化"),
        /** 内容方向转变 */
        TOPIC_DIRECTION_SHIFT("内容方向转变"),
        /** 增长趋势切换 */
        GROWTH_TREND_SHIFT("增长趋势转变");

        private final String label;

        ChangeType(String label) {
            this.label = label;
        }

        /**
         * 获取变化类型的中文标签。
         *
         * @return 中文标签
         */
        public String label() {
            return label;
        }
    }

    /** 严重程度 / 趋向枚举 */
    public enum Severity {
        /** 正向变化（利好） */
        POSITIVE,
        /** 警告（需关注） */
        WARNING,
        /** 一般信息 */
        INFO
    }
}
