package com.contentops.orchestrator.gateway;

import com.contentops.common.dto.*;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Mock 模式 Agent 网关 — 本地模拟所有 Agent 返回，用于开发和测试。
 *
 * <p>当 {@code contentops.mode=mock} 时激活此实现。
 * 无需启动任何其他 Agent 服务，工作流即可完整跑完所有 6 个阶段，
 * 便于演示、前端联调、管线逻辑验证。
 *
 * <p>Mock 数据为真实业务结构的简化版，确保前端展示和状态流转正常。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "contentops.mode", havingValue = "mock")
public class MockAgentGateway implements AgentGateway {

    private static final int MOCK_DELAY_MS = 500; // 模拟 Agent 处理延迟

    // ══════════════════ 同步调用（Mock 实现） ══════════════════

    @Override
    public AgentResponse<Map<String, Object>> callTopic(AgentTaskRequest request) {
        log.info("[Mock] TopicAgent 模拟执行: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("topics", Arrays.asList(
                Map.of(
                        "title", "3 个让你效率翻倍的时间管理方法",
                        "angle", "个人经验分享 + 实用工具推荐",
                        "targetPlatforms", Arrays.asList("小红书", "公众号")
                ),
                Map.of(
                        "title", "为什么你总是拖延？科学解释来了",
                        "angle", "心理学角度剖析 + 行动指南",
                        "targetPlatforms", Arrays.asList("小红书", "抖音")
                ),
                Map.of(
                        "title", "普通人如何通过副业月入过万",
                        "angle", "真实案例拆解 + 路径规划",
                        "targetPlatforms", Arrays.asList("公众号", "知乎")
                )
        ));
        data.put("trendingKeywords", Arrays.asList("效率提升", "时间管理", "副业赚钱", "自律", "成长"));
        data.put("competitiveAnalysis", "当前赛道内容较多，但深度内容稀缺，建议走「方法论+案例」差异化路线");
        data.put("recommendedDirection", "3 个让你效率翻倍的时间管理方法");

        return successResponse(AgentStage.TOPIC_PLANNING.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentExecute(AgentTaskRequest request) {
        return callContentDraft(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentOutline(AgentTaskRequest request) {
        log.info("[Mock] ContentAgent 大纲生成: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("outline", Arrays.asList(
                Map.of("level", 1, "title", "引言：为什么时间管理对你很重要", "content", "从现代人的焦虑切入，引出时间管理的核心价值"),
                Map.of("level", 1, "title", "方法一：番茄工作法", "content", "25分钟专注+5分钟休息的科学原理与实操"),
                Map.of("level", 1, "title", "方法二：四象限法则", "content", "重要紧急矩阵，教你区分优先级"),
                Map.of("level", 1, "title", "方法三：时间块规划", "content", "把一天切成块，每块专注一件事"),
                Map.of("level", 1, "title", "结语：从今天开始改变", "content", "鼓励行动，附7天挑战计划")
        ));
        data.put("estimatedWordCount", 2500);
        data.put("toneDescription", "亲切实用，像朋友分享经验");

        return successResponse(AgentStage.CONTENT_CREATION.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentDraft(AgentTaskRequest request) {
        log.info("[Mock] ContentAgent 初稿生成: workflowId={}", request.getWorkflowId());
        simulateDelay();

        AccountProfile profile = request.getAccountProfile();
        String niche = profile != null ? profile.getNiche() : "个人成长";
        String audience = profile != null ? profile.getTargetAudience() : "20-30岁年轻人";

        Map<String, Object> data = new HashMap<>();
        data.put("title", "3 个让你效率翻倍的时间管理方法，亲测有效");
        data.put("content", String.format(
                "# 3 个让你效率翻倍的时间管理方法，亲测有效%n%n" +
                "你好呀～今天想和大家聊聊「时间管理」这个话题。%n%n" +
                "作为一个在%s领域深耕的创作者，我经常被问到：「你是怎么做到每天更新还能保持质量的？」%n%n" +
                "其实答案很简单：不是我比别人时间多，而是我用对了方法。%n%n" +
                "## 方法一：番茄工作法 🍅%n%n" +
                "25分钟专注工作 + 5分钟休息，如此循环。听起来很简单，但真的有效！%n%n" +
                "## 方法二：四象限法则 📊%n%n" +
                "把任务按「重要/紧急」分成四个象限，优先处理重要但不紧急的事。%n%n" +
                "## 方法三：时间块规划 ⏰%n%n" +
                "把一天的时间切成「块」，每块只做一件事。%n%n" +
                "---%n%n" +
                "以上就是我亲测有效的3个方法，适合%s的你。%n%n" +
                "从今天开始试试吧！有问题评论区见～",
                niche, audience));
        data.put("wordCount", 1800);
        data.put("hashtags", Arrays.asList("#时间管理", "#效率提升", "#个人成长", "#自律"));
        data.put("seoKeywords", Arrays.asList("时间管理方法", "效率提升技巧", "如何提高专注力"));

        return successResponse(AgentStage.CONTENT_CREATION.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageExecute(AgentTaskRequest request) {
        return callImageGenerate(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageStyles(AgentTaskRequest request) {
        log.info("[Mock] ImageAgent 风格方向: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("styleDirections", Arrays.asList(
                Map.of(
                        "name", "极简插画风",
                        "description", "扁平化设计，暖色调，适合小红书封面",
                        "colorPalette", Arrays.asList("#FF6B6B", "#FFE66D", "#4ECDC4"),
                        "previewDescription", "一个专注工作的人物剪影，背景是柔和的渐变"
                ),
                Map.of(
                        "name", "3D 卡通风",
                        "description", "可爱的 3D 渲染风格，年轻活泼",
                        "colorPalette", Arrays.asList("#A8E6CF", "#FFD3B6", "#FFAAA5"),
                        "previewDescription", "一个番茄时钟的 3D 模型，旁边飘着效率小图标"
                ),
                Map.of(
                        "name", "信息图风格",
                        "description", "数据可视化风格，专业感强",
                        "colorPalette", Arrays.asList("#2C3E50", "#3498DB", "#ECF0F1"),
                        "previewDescription", "时间管理四象限的信息图，清晰直观"
                )
        ));
        data.put("recommendation", "推荐使用「极简插画风」，与目标受众审美匹配度最高");

        return successResponse(AgentStage.IMAGE_DESIGN.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageGenerate(AgentTaskRequest request) {
        log.info("[Mock] ImageAgent 批量生图: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("images", Arrays.asList(
                Map.of(
                        "id", "img-001",
                        "url", "https://picsum.photos/seed/cover1/1080/1440",
                        "purpose", "封面图",
                        "style", "极简插画风"
                ),
                Map.of(
                        "id", "img-002",
                        "url", "https://picsum.photos/seed/method1/1080/1080",
                        "purpose", "正文配图-方法一",
                        "style", "极简插画风"
                ),
                Map.of(
                        "id", "img-003",
                        "url", "https://picsum.photos/seed/method2/1080/1080",
                        "purpose", "正文配图-方法二",
                        "style", "极简插画风"
                ),
                Map.of(
                        "id", "img-004",
                        "url", "https://picsum.photos/seed/method3/1080/1080",
                        "purpose", "正文配图-方法三",
                        "style", "极简插画风"
                )
        ));
        data.put("totalCount", 4);
        data.put("styleUsed", "极简插画风");

        return successResponse(AgentStage.IMAGE_DESIGN.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callPublish(AgentTaskRequest request) {
        log.info("[Mock] PublishAgent 排版发布: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("publishedPlatforms", Arrays.asList(
                Map.of("platform", "小红书", "status", "success", "postUrl", "https://xhslink.com/mock/1"),
                Map.of("platform", "公众号", "status", "success", "postUrl", "https://mp.weixin.qq.com/mock/1"),
                Map.of("platform", "抖音", "status", "success", "postUrl", "https://v.douyin.com/mock/1")
        ));
        data.put("totalPlatforms", 3);
        data.put("successCount", 3);
        data.put("scheduledTime", LocalDateTime.now().plusHours(1).toString());

        return successResponse(AgentStage.PUBLISHING.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callAnalysis(AgentTaskRequest request) {
        log.info("[Mock] AnalysisAgent 数据分析: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("platformMetrics", Arrays.asList(
                Map.of(
                        "platform", "小红书",
                        "views", 12580,
                        "likes", 892,
                        "comments", 156,
                        "collects", 423,
                        "followerGain", 89
                ),
                Map.of(
                        "platform", "公众号",
                        "views", 3420,
                        "likes", 156,
                        "comments", 42,
                        "shares", 287,
                        "followerGain", 67
                )
        ));
        data.put("topPerformingContent", "3 个让你效率翻倍的时间管理方法");
        data.put("trendInsight", "本月「效率工具」相关内容互动率环比提升 23%，建议增加相关选题");
        data.put("recommendations", Arrays.asList(
                "增加「工具推荐」类选题，用户兴趣度高",
                "发布时间建议调整到晚上 8-10 点",
                "封面图测试暖色调，点击率更高"
        ));

        return successResponse(AgentStage.DATA_ANALYSIS.getCode(), data, request.getWorkflowId());
    }

    @Override
    public AgentResponse<Map<String, Object>> callOptimize(AgentTaskRequest request) {
        log.info("[Mock] OptimizeAgent 优化迭代: workflowId={}", request.getWorkflowId());
        simulateDelay();

        Map<String, Object> data = new HashMap<>();
        data.put("titleOptimization", "3个时间管理方法，让你效率翻倍（亲测有效）");
        data.put("contentImprovements", Arrays.asList(
                "开头增加「痛点共鸣」，提升完读率",
                "中间加入「3秒自测」互动环节，增加评论",
                "结尾增加「7天挑战」行动号召，提升收藏"
        ));
        data.put("hashtagOptimization", Arrays.asList("#时间管理", "#效率", "#自律", "#个人成长", "#职场干货"));
        data.put("nextTopicSuggestions", Arrays.asList(
                "推荐 5 款我常用的效率 App",
                "早起 30 天，我的生活发生了什么变化",
                "为什么你学了那么多时间管理还是没用"
        ));

        return successResponse(AgentStage.OPTIMIZATION.getCode(), data, request.getWorkflowId());
    }

    // ══════════════════ 讨论模式（Mock 实现） ══════════════════

    @Override
    public AgentResponse<DiscussionResponse> startDiscussion(Map<String, Object> request) {
        log.info("[Mock] DiscussionAgent 开始讨论");
        simulateDelay();

        DiscussionResponse response = new DiscussionResponse();
        response.setSessionId(UUID.randomUUID().toString());
        response.setMessage("你好！很高兴和你一起探索内容选题。为了帮你找到最合适的方向，我想先了解几个问题：\n\n1. 你想做的内容领域是？（比如：职场、成长、科技、生活...）\n2. 你的目标受众是哪类人？\n3. 你希望内容达到什么效果？（涨粉/变现/个人品牌/...）");
        response.setQuestions(Arrays.asList(
                "你想做的内容领域是？",
                "你的目标受众是哪类人？",
                "你希望内容达到什么效果？"
        ));
        response.setTurnCount(1);

        return AgentResponse.success("discussion", response);
    }

    @Override
    public AgentResponse<DiscussionResponse> chatDiscussion(String sessionId, Map<String, Object> request) {
        log.info("[Mock] DiscussionAgent 对话: sessionId={}", sessionId);
        simulateDelay();

        String userMessage = (String) request.get("message");
        DiscussionResponse response = new DiscussionResponse();
        response.setSessionId(sessionId);
        response.setMessage(String.format(
                "明白了！你提到「%s」，这个方向很有潜力。%n%n" +
                "基于你的描述，我有几个建议方向：%n%n" +
                "1. **干货方法论** — 系统分享知识，建立专业度%n" +
                "2. **真实经历分享** — 用故事打动读者，引发共鸣%n" +
                "3. **工具/资源推荐** — 实用信息，收藏率高%n%n" +
                "你更倾向于哪个方向？或者有其他想法？",
                userMessage != null ? userMessage : "你的内容方向"));
        response.setTurnCount(2);
        response.setClarifyingQuestion("你更倾向于哪个方向？");

        return AgentResponse.success("discussion", response);
    }

    @Override
    public AgentResponse<TopicPlanResult> finalizeDiscussion(String sessionId) {
        log.info("[Mock] DiscussionAgent 结束讨论: sessionId={}", sessionId);
        simulateDelay();

        TopicPlanResult result = new TopicPlanResult();
        result.setTopics(Arrays.asList(
                Map.of("title", "方向一：干货方法论", "angle", "系统分享知识"),
                Map.of("title", "方向二：真实经历分享", "angle", "故事化表达"),
                Map.of("title", "方向三：工具资源推荐", "angle", "实用信息集合")
        ));
        result.setRecommendedDirection("方向一：干货方法论");
        result.setRationale("干货内容的搜索流量稳定，适合长期积累个人品牌");

        return AgentResponse.success(AgentStage.TOPIC_PLANNING.getCode(), result);
    }

    @Override
    public AgentResponse<DiscussionSession> getDiscussionSession(String sessionId) {
        DiscussionSession session = new DiscussionSession();
        session.setSessionId(sessionId);
        session.setStatus("active");
        session.setTurnCount(2);

        return AgentResponse.success("discussion", session);
    }

    @Override
    public AgentResponse<Void> clearDiscussionSession(String sessionId) {
        log.info("[Mock] DiscussionAgent 清除会话: sessionId={}", sessionId);
        return AgentResponse.success("discussion", null);
    }

    // ══════════════════ 工具方法 ══════════════════

    private AgentResponse<Map<String, Object>> successResponse(String stage,
                                                                Map<String, Object> data,
                                                                String workflowId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("workflowId", workflowId);
        metadata.put("mockMode", true);
        return AgentResponse.success(stage, data, metadata);
    }

    private void simulateDelay() {
        try {
            Thread.sleep(MOCK_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
