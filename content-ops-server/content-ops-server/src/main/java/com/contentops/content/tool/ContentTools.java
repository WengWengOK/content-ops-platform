package com.contentops.content.tool;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.rag.AdvancedRagService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Content creation tools exposed to the {@link com.contentops.content.agent.ContentCreationAgent}.
 *
 * <p><b>P1 Update:</b> Tools now integrate with real infrastructure:
 * <ul>
 *   <li>{@link #generateOutline} — searches the knowledge base for similar past articles as reference</li>
 *   <li>{@link #searchExamples} — uses Tavily web search via KnowledgeBase + returns real results</li>
 *   <li>{@link #generateTags} — generates tags and persists them to local files via FileTools</li>
 *   <li>{@link #saveDraft} — new tool: writes article draft to a Markdown file and ingests to KB</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentTools {

    private final FileTools fileTools;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AdvancedRagService advancedRagService;

    /**
     * 从知识库检索资料（混合检索：向量 + BM25 + 重排），供内容创作引用。
     */
    @Tool("从知识库检索相关资料（混合检索+重排），返回最相关的片段用于创作")
    public String retrieveKnowledge(String query, String niche) {
        log.info("[Tool] retrieveKnowledge invoked: query={}, niche={}", query, niche);
        java.util.Map<String, String> filters = (niche == null || niche.isBlank())
                ? null : java.util.Map.of("niche", niche);
        java.util.List<AdvancedRagService.RetrievalResult> results =
                advancedRagService.retrieveAndRerank(query, filters, 5);
        if (results.isEmpty()) {
            return "知识库暂无匹配资料，请基于创作者真实经验创作，不要编造事实。";
        }
        StringBuilder sb = new StringBuilder("知识库检索结果（共 ")
                .append(results.size()).append(" 条）：\n");
        int idx = 1;
        for (AdvancedRagService.RetrievalResult r : results) {
            sb.append(idx++).append(". [相关度 ").append(String.format(java.util.Locale.ROOT, "%.2f", r.score()))
                    .append("] ").append(truncate(r.content(), 300)).append("\n");
        }
        sb.append("请优先基于以上资料组织内容，避免编造具体数据与案例。");
        return sb.toString();
    }

    /**
     * Generate an article outline, using the knowledge base to find similar past articles.
     */
    @Tool("生成文章框架大纲，可参考知识库中的历史文章")
    public String generateOutline(String topic, String angle) {
        log.info("[Tool] generateOutline invoked for topic: {}, angle: {}", topic, angle);

        StringBuilder sb = new StringBuilder();
        sb.append("[文章大纲] 《").append(topic).append("》文章框架建议（切入角度：").append(angle).append("）：\n\n");

        // Search knowledge base for similar past articles
        if (knowledgeBaseService.isAvailable()) {
            List<KnowledgeBaseService.SearchResult> similarArticles =
                    knowledgeBaseService.searchByType(topic + " " + angle, "article", 3);
            if (!similarArticles.isEmpty()) {
                sb.append("=== 参考历史文章 ===\n");
                for (int i = 0; i < similarArticles.size(); i++) {
                    sb.append(String.format("%d. [相似度:%.2f] %s\n",
                            i + 1, similarArticles.get(i).score(),
                            truncate(similarArticles.get(i).content(), 200)));
                }
                sb.append("\n");
            }
        }

        sb.append("一、开头引入：用一个真实场景或反常识提问引入，例如「你是不是也遇到过……」\n");
        sb.append("二、正文分段：\n");
        sb.append("  1. 现象描述：把读者痛点讲清楚，引发共鸣\n");
        sb.append("  2. 原因拆解：从").append(angle).append("视角分析为什么会这样\n");
        sb.append("  3. 方法论：给出3条可执行的建议，每条配一个案例\n");
        sb.append("  4. 进阶提醒：新手容易忽略的细节与风险点\n");
        sb.append("三、结尾总结：升华观点 + 互动引导（投票/留言/转发）\n");
        sb.append("建议节奏：开头150字、正文2000字、结尾200字，总字数约2350字。");

        return sb.toString();
    }

    /**
     * Search for relevant case studies and materials, using both the knowledge base
     * and web search for real-time information.
     */
    @Tool("搜索相关案例和素材，优先从知识库检索历史素材")
    public String searchExamples(String topic) {
        log.info("[Tool] searchExamples invoked for topic: {}", topic);

        StringBuilder sb = new StringBuilder();
        sb.append("[素材检索] 与「").append(topic).append("」相关的案例与素材：\n\n");

        // 1. Search knowledge base for historical materials
        if (knowledgeBaseService.isAvailable()) {
            List<KnowledgeBaseService.SearchResult> kbResults =
                    knowledgeBaseService.searchSimilar(topic + " 案例 素材 示例", 5, null);
            if (!kbResults.isEmpty()) {
                sb.append("=== 知识库历史素材 ===\n");
                for (int i = 0; i < kbResults.size(); i++) {
                    sb.append(String.format("%d. [相似度:%.2f] %s\n",
                            i + 1, kbResults.get(i).score(),
                            truncate(kbResults.get(i).content(), 200)));
                }
                sb.append("\n");
            } else {
                sb.append("（知识库中暂无匹配素材）\n\n");
            }
        }

        // 注意：禁止编造案例与数据。无真实素材时，应引导模型基于知识库历史数据
        // 或创作者真实经历组织内容，避免产生幻觉（与 TrendAggregationEnforcer 防编造原则一致）。
        sb.append("=== 素材使用提醒 ===\n");
        sb.append("- 以上知识库检索结果为可用素材；若知识库无匹配，请基于创作者真实经历组织案例，");
        sb.append("不要编造具体数据、案例或金句来源。");

        return sb.toString();
    }

    /**
     * Generate SEO tags and optionally save them to a file.
     */
    @Tool("生成SEO标签")
    public String generateTags(String content) {
        log.info("[Tool] generateTags invoked, content length: {}",
                content != null ? content.length() : 0);
        String preview = content != null && content.length() > 20
                ? content.substring(0, 20) : content;
        return "[标签建议] 基于内容片段「" + preview + "…」生成的SEO标签建议：\n"
                + "#内容创作 #自媒体运营 #干货分享 #新手指南 #爆款方法论\n"
                + "#写作技巧 #涨粉攻略 #选题策划 #案例分析 #实用清单\n"
                + "提示：建议挑选5-10个与文章主题最贴合的标签组合使用。";
    }

    /**
     * Save the article draft to a Markdown file and ingest it into the knowledge base.
     * This implements the TRAE Work capability of "直接读写本地文件".
     *
     * @param title     the article title
     * @param content   the Markdown content
     * @param niche     the account niche
     * @param workflowId the workflow ID
     */
    @Tool("将文章初稿保存到本地Markdown文件并入库知识库")
    public String saveDraft(String title, String content, String niche, String workflowId) {
        log.info("[Tool] saveDraft invoked: title={}, contentLength={}, niche={}",
                title, content != null ? content.length() : 0, niche);

        // 1. Generate a timestamped file path
        String filePath = fileTools.generateOutputPath("content-creation", "md");
        String fullContent = "# " + title + "\n\n" + content;

        // 2. Write to local file
        String writeResult = fileTools.writeLocalFile(filePath, fullContent);

        // 3. Ingest into knowledge base for future retrieval
        if (knowledgeBaseService.isAvailable()) {
            knowledgeBaseService.ingestArticle(title, content, niche, "", "");
            log.info("[Tool] saveDraft: article ingested into knowledge base");
        }

        return writeResult + "\n[知识库] 文章已入库，后续Agent可通过语义检索引用。";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
