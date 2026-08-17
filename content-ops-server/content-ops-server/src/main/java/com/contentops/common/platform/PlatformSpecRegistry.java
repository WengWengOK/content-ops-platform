package com.contentops.common.platform;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 平台规格注册表 —— 集中维护四大平台的产出规则，并对外提供宽容解析。
 */
@Component
public class PlatformSpecRegistry {

    @Getter
    private final Map<ContentPlatform, PlatformSpec> specs = new EnumMap<>(ContentPlatform.class);

    public PlatformSpecRegistry() {
        register(xiaohongshuSpec());
        register(wechatSpec());
        register(douyinSpec());
        register(bilibiliSpec());
    }

    private void register(PlatformSpec spec) {
        specs.put(spec.getPlatform(), spec);
    }

    public PlatformSpec spec(ContentPlatform platform) {
        return specs.get(platform);
    }

    /** 宽容解析单个平台（code / 中文名 / 短码 / 别名）。 */
    public ContentPlatform resolve(String nameOrCode) {
        return ContentPlatform.from(nameOrCode);
    }

    /**
     * 宽容解析平台列表；无法识别的条目被忽略，全部无法识别时返回空列表。
     */
    public List<ContentPlatform> resolveAll(List<String> namesOrCodes) {
        List<ContentPlatform> result = new ArrayList<>();
        if (namesOrCodes == null) {
            return result;
        }
        for (String raw : namesOrCodes) {
            ContentPlatform platform = resolve(raw);
            if (platform != null && !result.contains(platform)) {
                result.add(platform);
            }
        }
        return result;
    }

    /** 生成单个平台的 Prompt 适配指令；无法解析时返回空串。 */
    public String guidance(String nameOrCode) {
        ContentPlatform platform = resolve(nameOrCode);
        if (platform == null) {
            return "";
        }
        return guidance(platform);
    }

    public String guidance(ContentPlatform platform) {
        PlatformSpec spec = specs.get(platform);
        return spec == null ? "" : spec.buildPromptFragment();
    }

    public String guidance(List<ContentPlatform> platforms) {
        StringBuilder sb = new StringBuilder();
        for (ContentPlatform platform : platforms) {
            String fragment = guidance(platform);
            if (!fragment.isBlank()) {
                sb.append(fragment);
            }
        }
        return sb.toString();
    }

    private PlatformSpec xiaohongshuSpec() {
        return new PlatformSpec(
                ContentPlatform.XIAOHONGSHU,
                "8-20 字，口语化、有悬念/冲突/数字，可带 1 个 emoji；忌完整陈述句、忌官方腔",
                "300-800 字；第一人称、像朋友分享；短段落（每段不超过 3 行），多用换行和 emoji 分隔；" +
                        "开头 1-2 句直接抛痛点或反常识结论，不要铺垫",
                "痛点/场景开头 → 方法分点（每点一个小标题或 emoji 引导）→ 亲测体验/效果 → 互动引导（评论区聊聊/求推荐）",
                "文末附 5-10 个 #话题标签，覆盖主题、人群、场景（如 #时间管理 #职场 #自律）",
                "3-9 张，3:4 竖图为主，首图为大字标题封面，正文配图信息密度高",
                "活泼、真实、有网感；允许适度夸张但必须有真实体验支撑",
                "禁止长段落、禁止公众号式正式文风、禁止无信息量的空话、禁止硬广口吻"
        );
    }

    private PlatformSpec wechatSpec() {
        return new PlatformSpec(
                ContentPlatform.WECHAT_OFFICIAL_ACCOUNT,
                "15-30 字，可带副标题（主标题+冒号/破折号）；信息明确、有价值感，避免过度标题党",
                "1500-3000 字深度长文；小标题分段，每段有明确论点；克制使用 emoji，多用加粗强调；" +
                        "需要有观点、有数据或案例支撑，像杂志深度文章",
                "引言（场景/问题）→ 问题拆解 → 方法论/案例展开 → 结论升华 + 行动建议；文末附摘要（≤120 字）",
                "不强制话题标签；可提供 1-3 个 SEO 关键词用于搜索优化",
                "封面 16:9 横图；正文配图 1-3 张辅助说明，不喧宾夺主",
                "专业、克制、有思考深度；可以有个人观点但要自圆其说",
                "禁止标题党过度承诺、禁止 emoji 堆砌、禁止碎片化流水账"
        );
    }

    private PlatformSpec douyinSpec() {
        return new PlatformSpec(
                ContentPlatform.DOUYIN,
                "≤30 字，热点词/关键词前置，口语化，可带数字或反差（如“3 个方法”、“别再”）",
                "300-800 字；卡片式图文排版，一图一文、信息密度高；每张配图配一句核心文案；" +
                        "第一行必须是钩子（痛点/结果/悬念），前 3 秒决定完读",
                "钩子开头 → 分图分点讲干货 → 结尾强引导（点赞/评论/关注，如“收藏慢慢看”）",
                "2-4 个，正文开头或结尾各放一部分，覆盖话题与人群",
                "3-6 张，9:16 或 3:4；首图为大字封面，文字占位明显、色彩对比强",
                "快节奏、口语化、有煽动力但不过度；用“你”直接对话",
                "禁止长篇大论、禁止首图无重点、禁止结尾无行动引导"
        );
    }

    private PlatformSpec bilibiliSpec() {
        return new PlatformSpec(
                ContentPlatform.BILIBILI,
                "≤30 字，可带“干货向/教程向/实测”等明确类型词；B 站用户偏好信息明确的标题",
                "800-2000 字；图文专栏或动态配文，允许长文 + 列表/加粗；每节有清晰小标题；" +
                        "内容要有知识增量或强观点，真诚比华丽更重要",
                "前言（为什么讲这个）→ 目录/要点预告 → 分节展开 → 总结 + 互动提问（评论区交流）",
                "3-5 个分区/话题标签（如 #干货分享 #教程 #学习）",
                "1-5 张，16:9 或 1:1；封面图有明确信息，正文图辅助说明",
                "真诚、有趣、有知识增量；可以适度玩梗，但不牺牲信息密度",
                "禁止空话套话、禁止标题与内容不符、禁止过度营销口吻"
        );
    }
}
