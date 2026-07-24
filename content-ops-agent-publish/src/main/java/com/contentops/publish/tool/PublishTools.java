package com.contentops.publish.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Publishing tools exposed to the {@link com.contentops.publish.agent.PublishingAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 * The implementations here return simulated data; in production they would delegate to real
 * platform APIs and rich-text converters.
 */
@Slf4j
@Component
public class PublishTools {

    @Tool("将Markdown转换为指定平台的富文本格式")
    public String convertToPlatformFormat(String markdown, String platform) {
        log.info("[Tool] convertToPlatformFormat invoked for platform: {}, markdown length: {}",
                platform, markdown != null ? markdown.length() : 0);
        return "[模拟结果] 已将Markdown转换为「" + platform + "」平台富文本格式：\n"
                + "- 公众号：保留加粗与引用，图片包裹 <img> 居中标签，段落间插入空行，生成可直接粘贴的HTML\n"
                + "- 小红书：去除Markdown符号，emoji替换关键词，每段控制在50字内，图片穿插标注 [图片1]\n"
                + "- 头条：转为基础HTML，小标题用 <h3>，文末追加引导关注模块\n"
                + "- 知乎：保留引用块与有序列表，转为知乎富文本兼容格式\n"
                + "输出预览（" + platform + "）：内容已适配，可读性评分 92/100，预计适配耗时 0.8秒。";
    }

    @Tool("优化段落长度和阅读节奏")
    public String optimizeReadability(String content, String platform) {
        log.info("[Tool] optimizeReadability invoked for platform: {}, content length: {}",
                platform, content != null ? content.length() : 0);
        return "[模拟结果] 针对「" + platform + "」平台优化阅读节奏：\n"
                + "- 段落拆分：超长段落（>200字）已拆为2-3段，关键句前置\n"
                + "- 节奏控制：开头短句抓注意力，中段适度展开，结尾收束引导互动\n"
                + "- 视觉留白：每2-3段插入配图位或分隔符，降低阅读疲劳\n"
                + "- 平台特性：" + platform + "推荐段落字数 "
                + ("小红书".equals(platform) ? "30-80字" : "80-150字")
                + "，已按此标准调整\n"
                + "优化后平均段落字数降低23%，预估完读率提升约15%。";
    }

    @Tool("生成发布检查清单")
    public String generateChecklist(String platform) {
        log.info("[Tool] generateChecklist invoked for platform: {}", platform);
        return "[模拟清单] 「" + platform + "」平台发布前检查清单：\n"
                + "1. 标题是否符合平台字数限制与调性（" + platform + "建议20字以内）\n"
                + "2. 封面图尺寸是否正确、无水印、与内容相关\n"
                + "3. 正文排版是否符合平台富文本规范\n"
                + "4. 配图位置是否合理、图片是否清晰\n"
                + "5. 标签/话题是否已添加且数量合规\n"
                + "6. 文末引导语（关注/点赞/留言）是否到位\n"
                + "7. 敏感词与广告法违禁词是否已排查\n"
                + "8. 原创声明与版权信息是否填写\n"
                + "9. 发布时间是否选择流量高峰时段\n"
                + "10. 草稿预览在手机端显示是否正常\n"
                + "提示：逐项确认后再执行发布，可大幅降低返工率。";
    }
}
