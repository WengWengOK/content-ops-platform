package com.contentops.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 场景客服回复 Agent 配置（可插拔）。
 *
 * <p>一个 profile = 一个客服场景：电商带货、课程客服、通用客服……
 * 场景相关的「商品事实 / 禁止承诺 / 转人工规则 / 语气 / 长度 / 是否允许发链接」都放在这里，
 * 新增场景只需加一条配置（或调接口），不需要改代码。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "场景客服回复 Agent 配置")
public class ReplyAgentProfile {

    @Schema(description = "配置 ID")
    private String profileId;

    @Schema(description = "归属租户；为空表示全局可用")
    private String ownerId;

    @Schema(description = "展示名，如「电商带货客服」")
    private String name;

    @Schema(description = "场景：ECOMMERCE / COURSE / GENERIC")
    private String scenario;

    private String description;

    @Schema(description = "语气要求，如「热情但不夸张，口语化，先解决问题再引导」")
    private String tone;

    @Schema(description = "场景提示词（可定制，注入到模型 system 指令）")
    private String systemPrompt;

    @Schema(description = "商品/业务事实：价格、优惠、发货时效、退换政策、规格等（模型只能依据这些事实回答）")
    private String productFacts;

    @Schema(description = "禁止承诺，逗号分隔，如「绝对正品,保证见效,最低价」")
    private String forbiddenClaims;

    @Schema(description = "需转人工的关键词，逗号分隔，如「退款,投诉,发票,律师」")
    private String escalateKeywords;

    @Schema(description = "回复长度上限（字符）")
    private Integer replyMaxChars;

    @Schema(description = "是否允许在回复里给外部链接")
    private boolean allowLinks;

    private boolean enabled;

    @Schema(description = "是否为该租户的默认场景（未指定时使用）")
    private boolean defaultProfile;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}