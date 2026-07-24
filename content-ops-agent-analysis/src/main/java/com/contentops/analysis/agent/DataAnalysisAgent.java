package com.contentops.analysis.agent;

import com.contentops.common.dto.AnalysisReport;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * LangChain4j AI Service for content operations data analysis.
 *
 * <p>The {@code @AiService} marker is retained for declarative intent, but the actual bean is
 * built programmatically in {@link com.contentops.analysis.config.AnalysisAgentConfig} using
 * {@code AiServices.builder()} so that the {@link com.contentops.analysis.tool.AnalysisTools}
 * are explicitly wired in.
 */
@AiService
@SystemMessage("""
        你是「数据分析Agent」，一个专业的自媒体数据分析师。你的核心任务是分析内容运营数据，输出可执行的洞察。

        工作原则：
        1. 接收后台导出的数据（阅读量、点赞、转发、评论、粉丝变化等）
        2. 按月分析趋势，而非单篇
        3. 分析哪些类型的文章表现好
        4. 分析哪些时间段发文效果更好
        5. 生成可视化图表数据
        6. 输出可执行的具体建议

        分析维度：
        - 内容类型分析：不同主题/类型的平均表现对比
        - 时间分析：星期几/时间段的表现差异
        - 互动分析：完读率、互动率最高的内容特征
        - 趋势分析：粉丝增长趋势、阅读量变化

        输出要求：
        - 核心指标摘要（平均阅读量、互动率、涨粉数等）
        - 各类内容表现对比
        - 时间段表现分析
        - 关键洞察列表
        - 具体建议列表
        - 图表数据（JSON格式，可用于前端渲染）
        """)
public interface DataAnalysisAgent {

    /**
     * Analyzes content performance data and produces a structured analysis report.
     *
     * <p>LangChain4j parses the structured {@link AnalysisReport} return type, asks the model
     * to conform to the derived JSON schema, and deserializes the response automatically.
     *
     * @param accountNiche              the account domain/niche (e.g. "个人成长")
     * @param rawData                   the raw exported performance data (reads, likes, shares, etc.)
     * @param timeRange                 the analysis time range (e.g. "2025-06-01 至 2025-06-30")
     * @param previousAnalysisSummary   summary text from the previous analysis cycle (may be null/empty)
     * @return a structured analysis report with metrics, category/time performance, insights and chart data
     */
    @UserMessage("""
            请对以下账号的运营数据进行分析：
            - 账号领域：{{accountNiche}}
            - 分析时间范围：{{timeRange}}
            - 原始数据：
            {{rawData}}
            - 上一周期分析摘要：{{previousAnalysisSummary}}

            请先调用可用工具计算平均表现指标、按内容类型分组分析、按发布时间分析最佳发文时段，
            并生成可视化图表数据。然后严格按照系统提示的输出要求，
            返回结构化的数据分析报告（核心指标摘要、各类内容表现对比、时间段表现分析、关键洞察列表、具体建议列表、图表数据）。
            """)
    AnalysisReport analyzePerformance(String accountNiche,
                                      String rawData,
                                      String timeRange,
                                      String previousAnalysisSummary);
}
