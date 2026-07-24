package com.contentops.content.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Content creation tools exposed to the {@link com.contentops.content.agent.ContentCreationAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 * The implementations here return simulated data; in production they would delegate to real
 * retrieval / SEO APIs.
 */
@Slf4j
@Component
public class ContentTools {

    @Tool("生成文章框架大纲")
    public String generateOutline(String topic, String angle) {
        log.info("[Tool] generateOutline invoked for topic: {}, angle: {}", topic, angle);
        return "[模拟大纲] 《" + topic + "》文章框架建议（切入角度：" + angle + "）：\n"
                + "一、开头引入：用一个真实场景或反常识提问引入，例如「你是不是也遇到过……」\n"
                + "二、正文分段：\n"
                + "  1. 现象描述：把读者痛点讲清楚，引发共鸣\n"
                + "  2. 原因拆解：从" + angle + "视角分析为什么会这样\n"
                + "  3. 方法论：给出3条可执行的建议，每条配一个案例\n"
                + "  4. 进阶提醒：新手容易忽略的细节与风险点\n"
                + "三、结尾总结：升华观点 + 互动引导（投票/留言/转发）\n"
                + "建议节奏：开头150字、正文2000字、结尾200字，总字数约2350字。";
    }

    @Tool("搜索相关案例和素材")
    public String searchExamples(String topic) {
        log.info("[Tool] searchExamples invoked for topic: {}", topic);
        return "[模拟素材] 与「" + topic + "」相关的案例与素材：\n"
                + "- 案例A：某博主围绕「" + topic + "」做了一期对比测评，单篇阅读量破10万，评论区高互动集中在「第3点太真实了」。\n"
                + "- 案例B：某行业报告数据显示，" + topic + "相关搜索量同比上涨42%，25-35岁用户占比最高。\n"
                + "- 案例C：一位素人账号用「故事+复盘」结构写" + topic + "，粉丝从0涨到8000只用了一个月。\n"
                + "- 金句素材：「真正的高手，不是不踩坑，而是踩完坑能把坑画成地图。」\n"
                + "- 数据支撑：根据模拟调研，72%的读者更愿意收藏「带步骤清单」的文章。";
    }

    @Tool("生成SEO标签")
    public String generateTags(String content) {
        log.info("[Tool] generateTags invoked, content length: {}",
                content != null ? content.length() : 0);
        String preview = content != null && content.length() > 20
                ? content.substring(0, 20) : content;
        return "[模拟标签] 基于内容片段「" + preview + "…」生成的SEO标签建议：\n"
                + "#内容创作 #自媒体运营 #干货分享 #新手指南 #爆款方法论\n"
                + "#写作技巧 #涨粉攻略 #选题策划 #案例分析 #实用清单\n"
                + "提示：建议挑选5-10个与文章主题最贴合的标签组合使用，避免堆砌过多影响推荐。";
    }
}
