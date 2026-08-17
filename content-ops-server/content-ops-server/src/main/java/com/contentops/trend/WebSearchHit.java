package com.contentops.trend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * 全网搜索聚合结果条目（tavily-web / tavily-news / trend 等来源）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "全网搜索聚合结果")
public class WebSearchHit {

    @Schema(description = "来源：tavily-web / tavily-news")
    private String source;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "原文链接")
    private String url;

    @Schema(description = "内容片段")
    private String content;

    @Schema(description = "相关性得分（0-1，Tavily 返回）")
    private Double score;
}
