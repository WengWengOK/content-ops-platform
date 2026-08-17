package com.contentops.trend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * AI 分析结果：热点与监控关键词/账号定位的相关性、可信度（真假识别）与一句话摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI 热点分析结果")
public class TrendAnalysis {

    @Schema(description = "相关性评分 0-100（越高越贴合关键词/定位）")
    private Integer relevance;

    @Schema(description = "可信度评分 0-100（越低越疑似谣言/标题党）")
    private Integer credibility;

    @Schema(description = "一句话摘要（≤50 字）")
    private String summary;

    @Schema(description = "是否疑似谣言/夸大（真假识别）")
    private Boolean riskFlag;
}
