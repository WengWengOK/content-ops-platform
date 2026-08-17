package com.contentops.trend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 热点监控方向订阅：用户自定义关注的行业/关键词。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "热点监控方向订阅")
public class TrendSubscription {

    @Schema(description = "订阅 ID")
    private String subscriptionId;

    @Schema(description = "归属用户 ID")
    private String ownerId;

    @Schema(description = "监控关键词/行业方向，如：AI、新能源、职场")
    private String keyword;

    @Schema(description = "是否启用")
    private boolean enabled;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
