package com.contentops.common.knowledge.qa;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库问答 + RAG 错误识别配置（{@code contentops.knowledge.qa.*}）。
 *
 * <p>对应「RAG 错误分四类 + 五层识别」：
 * <ol>
 *   <li>检索侧信号（相关条数 / 最高分 / 改写轮次 / 双路分歧）→ {@link RetrievalRelevanceEvaluator}</li>
 *   <li>生成侧引用校验 → {@link CitationGuard}</li>
 *   <li>答案-证据一致性（忠实度）→ {@link AnswerFaithfulnessChecker}</li>
 *   <li>用户隐式信号归因 → {@link QaImplicitSignalService}</li>
 *   <li>离线聚类与回归 → {@link QaOfflineMiner}</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.knowledge.qa")
public class QaProperties {

    /** 是否启用知识库问答（关闭后 /kb/qa/ask 返回未启用提示） */
    private boolean enabled = true;

    /** 检索返回条数 */
    private int topK = 5;

    /** 判定「单条证据相关」的得分门槛（重排分 / 融合分） */
    private double relevantScoreThreshold = 0.5;

    /** 至少要有多少条相关证据才算「检索充分」 */
    private int minRelevantCount = 1;

    /** 最高分门槛：低于此值视为「向量路最高相似度偏低」，命中检索侧风险信号 */
    private double minTopScore = 0.35;

    /** 检索不充分时最多改写几轮（超过仍不充分 → 拒答 + 记高风险检索日志） */
    private int maxRewriteRounds = 2;

    /** 检索不充分时是否直接拒答（关闭则照常生成，但会打风险标记） */
    private boolean refuseOnInsufficient = true;

    /** 关键词路 / 向量路 topK 集合重合度低于该值 → 双路结果分歧信号 */
    private double pathDivergenceThreshold = 0.34;

    /** 双路分歧检测的取样条数 */
    private int pathCompareSize = 5;

    /** 拒答话术（可定制） */
    private String refusalMessage =
            "抱歉，知识库里没有找到能支撑这个问题的资料。我先记下来，麻烦你补充一下具体场景，或者我帮你转人工确认。";

    /** 注入提示词的单条证据最大字符数（控制 token） */
    private int maxEvidenceChars = 1200;

    /** 忠实度校验配置 */
    private Faithfulness faithfulness = new Faithfulness();

    /** 隐式信号配置 */
    private Implicit implicit = new Implicit();

    /** 答案-证据一致性校验参数 */
    @Data
    public static class Faithfulness {

        /** 是否启用忠实度校验 */
        private boolean enabled = true;

        /** 判定「支持」的证据覆盖率门槛（结论有多少落在证据里） */
        private double supportThreshold = 0.6;

        /** 判定「部分支持」的证据覆盖率门槛（低于此值判为不支持） */
        private double partialThreshold = 0.3;

        /** 不支持占比超过该值 → HIGH（进人工复核清单） */
        private double unsupportedRatioThreshold = 0.3;

        /** 「不支持 + 部分支持」占比超过该值 → MEDIUM（抽样复核） */
        private double partialRatioThreshold = 0.5;

        /** 太短的句子（标点碎片）不作为结论核查 */
        private int minClaimChars = 4;

        /** 是否对高风险样本再送强模型复核（成本更高，默认关） */
        private boolean llmJudgeEnabled = false;

        /** 单次最多送审多少条结论 */
        private int llmJudgeMaxClaims = 5;

        /** 规则判定置信度低于该值的结论才送模型复核（模糊带） */
        private double llmJudgeMinRuleConfidence = 0.6;
    }

    /** 用户隐式信号参数 */
    @Data
    public static class Implicit {

        /** 是否启用隐式信号归因 */
        private boolean enabled = true;

        /** 「短时间重复提问」的时间窗（分钟） */
        private int repeatWindowMinutes = 30;

        /** 重复提问的相似度门槛（字二元组 Jaccard） */
        private double repeatSimilarity = 0.45;

        /** 引用章节停留低于该毫秒数 → 视为「点开就返回」 */
        private int citationDwellMs = 2000;

        /** 质疑/追问标记词：出现即认为用户对上个答案不满 */
        private List<String> doubtMarkers = List.of(
                "不对", "不是这样", "还是找不到", "没找到", "没有用", "答非所问", "答错了", "不准确",
                "看不懂", "没解决", "胡编", "乱说");

        /** 重复提问命中后，向前回溯多少条历史问答做比对 */
        private int repeatLookback = 20;
    }
}
