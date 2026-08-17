package com.contentops.publish.render;

import com.contentops.common.platform.MarkdownConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders finished works into platform-ready downloadable artifacts.
 *
 * <ul>
 *   <li><b>WeChat Official Account</b>: fully inline-styled HTML that can be pasted
 *       directly into the WeChat MP editor (WeChat strips CSS classes and
 *       {@code <style>} blocks, so every rule is inlined).</li>
 *   <li><b>Xiaohongshu</b>: a set of standalone 1080×1440 carousel card pages.
 *       Open any card in a browser and screenshot / export PNG at 1080×1440.</li>
 * </ul>
 *
 * <p>This is an original implementation. It borrows only the general ideas
 * (paragraph-based pagination, inline styles for WeChat) from public open-source
 * tools such as RedBookCards / md2red / wechat-styler; no third-party code is copied.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialCardRenderer {

    private static final int CARD_WIDTH = 1080;
    private static final int CARD_HEIGHT = 1440;
    private static final int CARD_PADDING = 72;
    private static final int HEADER_HEIGHT = 60;
    private static final int FOOTER_HEIGHT = 70;
    /** Vertical space available for body content on one card (px). */
    private static final int BODY_HEIGHT = CARD_HEIGHT - CARD_PADDING * 2 - HEADER_HEIGHT - FOOTER_HEIGHT;
    /** Usable content width (px). */
    private static final int CONTENT_WIDTH = CARD_WIDTH - CARD_PADDING * 2;
    /** Estimated CJK chars per line at body font size 32px. */
    private static final int CHARS_PER_LINE = Math.max(1, CONTENT_WIDTH / 32);
    /** Estimated line height (px) for body text. */
    private static final int LINE_HEIGHT = 55;
    /** Long paragraphs are chunked to keep them inside one card. */
    private static final int PARAGRAPH_CHUNK = 400;
    private static final int MAX_CARDS = 50;

    private static final Pattern TOP_LEVEL_BLOCK = Pattern.compile(
            "<(h[1-6]|p|ul|ol|blockquote|pre)(\\s[^>]*)?>(?s:.*?)</\\1>|<hr\\s*/?>|<img\\s[^>]*/>");
    private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");
    private static final Pattern LIST_ITEM = Pattern.compile("<li>");
    private static final Pattern HEADING = Pattern.compile("<h([1-6])>");

    /** 卡片 HTML 模板（CSS 变量 + 版式参数化，见 resources/render/card-template.html） */
    private static final String CARD_TEMPLATE = loadCardTemplate();

    private final MarkdownConverter markdownConverter;
    private final CardMeasurer cardMeasurer;

    private static String loadCardTemplate() {
        try (InputStream in = SocialCardRenderer.class.getResourceAsStream("/render/card-template.html")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: /render/card-template.html");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load card template", e);
        }
    }

    // ───────────────────────────── 公众号 ─────────────────────────────

    /**
     * Render a paste-ready WeChat Official Account article.
     *
     * @param title         article title
     * @param markdown      Markdown source
     * @param coverImageUrl optional cover image URL
     * @return full standalone HTML with all styles inlined
     */
    public String renderWechatArticle(String title, String markdown, String coverImageUrl) {
        String safeTitle = escapeHtml(title == null ? "" : title);
        String body = markdownConverter.convertToWechatHtml(markdown == null ? "" : markdown);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
                .append("<title>").append(safeTitle).append("</title></head>\n")
                .append("<body style=\"margin:0;background:#f2f2f2;padding:24px 0;\">\n")
                .append("<section style=\"max-width:677px;margin:0 auto;background:#ffffff;")
                .append("padding:36px 24px;border-radius:8px;")
                .append("box-shadow:0 2px 12px rgba(0,0,0,0.06);\">\n");
        if (!safeTitle.isBlank()) {
            html.append("<h1 style=\"font-size:24px;line-height:1.5;font-weight:bold;color:#1a1a1a;")
                    .append("margin:0 0 20px;\">").append(safeTitle).append("</h1>\n");
        }
        if (coverImageUrl != null && !coverImageUrl.isBlank()) {
            html.append("<img src=\"").append(escapeAttr(coverImageUrl)).append("\" alt=\"cover\" ")
                    .append("style=\"max-width:100%;border-radius:6px;margin:0 0 24px;display:block;\"/>\n");
        }
        html.append(body).append('\n');
        html.append("<p style=\"color:#bbbbbb;font-size:13px;text-align:center;margin:36px 0 0;\">")
                .append("— 本文由 ContentOps 生成 —</p>\n");
        html.append("</section></body></html>");
        return html.toString();
    }

    // ───────────────────────────── 小红书轮播卡片 ─────────────────────────────

    /**
     * Render a Xiaohongshu carousel (1080×1440 cards) from Markdown and generated images.
     *
     * @param title         article title (shown on the first card)
     * @param markdown      Markdown source
     * @param coverImageUrl optional cover image (first card)
     * @param images        generated images: maps with {@code url}/{@code imageUrl} and {@code purpose}
     * @param publishMode   {@code text-cover} / {@code image-text} / {@code full-image}
     * @param theme         theme name: xhs (default), fresh, warm, minimal; {@code null} for default
     * @return list of standalone HTML pages, one per card
     */
    public List<String> renderXiaohongshuCarousel(String title, String markdown, String coverImageUrl,
                                                  List<Map<String, Object>> images,
                                                  String publishMode, String theme) {
        return renderXiaohongshuCarouselDetailed(
                title, markdown, coverImageUrl, images, publishMode, theme, "classic").cards();
    }

    /** 单张卡片的校验记录（P1 校验报告用）。 */
    public record CardQa(int cardIndex, int overflowPx, int reflowedBlocks, boolean measured) {
        public String summary() {
            return "第 " + cardIndex + " 张卡: " + (overflowPx <= 2 ? "OK" : "溢出 " + overflowPx + "px")
                    + (measured ? "（真实渲染测量）" : "（估算）")
                    + (reflowedBlocks > 0 ? "，已自动重排 " + reflowedBlocks + " 个块" : "");
        }
    }

    /** 轮播渲染结果：卡片 HTML + 校验记录。 */
    public record CarouselResult(List<String> cards, List<CardQa> qa) {
    }

    /**
     * 合集按类型绑定主题：返回该类型对应的主题 code（xhs/fresh/warm/minimal）。
     * 同一作品放进不同类型合集，导出时自动切换视觉变体。
     */
    public static String themeCodeForCollectionType(String type) {
        return Theme.forType(type).getCode();
    }

    /**
     * 渲染小红书轮播卡片（可指定主题与版式），并对每张卡做溢出测量与自动重排。
     *
     * @param theme 主题名（xhs/fresh/warm/minimal 或中文），可为 null 用默认
     * @param layout 版式：classic（默认）/ hero（首卡封面大图）
     */
    public CarouselResult renderXiaohongshuCarouselDetailed(String title, String markdown,
                                                            String coverImageUrl,
                                                            List<Map<String, Object>> images,
                                                            String publishMode, String theme,
                                                            String layout) {
        Theme t = Theme.from(theme);
        String safeTitle = title == null ? "" : title;
        String cover = firstNonBlank(coverImageUrl, findCoverUrl(images));
        Deque<String> inlineImages = new ArrayDeque<>(findInlineImageUrls(images));
        boolean fullImage = "full-image".equalsIgnoreCase(publishMode);

        List<Block> blocks = splitBlocks(markdownConverter.convertToHtml(markdown == null ? "" : markdown));
        List<Page> pages = new ArrayList<>();
        Page first = new Page();
        first.title = safeTitle;
        first.coverUrl = cover;
        first.height = (safeTitle.isBlank() ? 0 : 150) + (cover.isBlank() ? 0 : 560);
        pages.add(first);

        if (fullImage) {
            while (!inlineImages.isEmpty()) {
                Page imagePage = new Page();
                imagePage.imageCard = true;
                imagePage.imageUrl = inlineImages.pollFirst();
                pages.add(imagePage);
            }
        }

        int blockCountSinceImage = 0;
        for (Block block : blocks) {
            List<Block> toAdd = block.height > BODY_HEIGHT ? splitOversizedBlock(block) : List.of(block);
            for (Block b : toAdd) {
                Page current = currentPage(pages);
                if (current.height + b.height > BODY_HEIGHT && !current.blocks.isEmpty()) {
                    current = newPage(pages);
                }
                if (current.height + b.height > BODY_HEIGHT && current.blocks.isEmpty()) {
                    // Still too big after page break: keep going with overflow hidden on the card;
                    // 后续真实测量会触发自动重排。
                    current.blocks.add(b);
                    current.height += b.height;
                    continue;
                }
                current.blocks.add(b);
                current.height += b.height;
                blockCountSinceImage++;
            }

            // image-text mode: interleave one inline image after every heading or every 3rd block
            if (!fullImage && !inlineImages.isEmpty()) {
                boolean afterHeading = block.htmlTag != null
                        && block.htmlTag.matches("h[2-6]");
                if (afterHeading || blockCountSinceImage >= 3) {
                    Page current = currentPage(pages);
                    int imageHeight = 560;
                    if (current.height + imageHeight > BODY_HEIGHT && !current.blocks.isEmpty()) {
                        current = newPage(pages);
                    }
                    current.blocks.add(new Block("img", imageHtml(inlineImages.pollFirst()), imageHeight));
                    current.height += imageHeight;
                    blockCountSinceImage = 0;
                }
            }

            if (pages.size() >= MAX_CARDS) {
                break;
            }
        }

        if (pages.size() > 1 && pages.get(0).blocks.isEmpty()) {
            // Nothing but title on the first card: merge it into the second card
            Page second = pages.get(1);
            if (second.blocks.isEmpty() && second.imageCard) {
                second.blocks.add(0, new Block("h2",
                        "<h2>" + escapeHtml(safeTitle) + "</h2>", 70));
            }
        }

        // P1：真实渲染测量 + 自动重排（R1 溢出检测思路，最多 2 轮）
        List<Integer> reflowed = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            reflowed.add(0);
        }
        for (int round = 0; round < 2; round++) {
            List<String> cardsHtml = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                cardsHtml.add(renderCard(t, pages.get(i), i + 1, pages.size(), layout));
            }
            Map<String, CardMeasurer.OverflowResult> overflow = new java.util.LinkedHashMap<>();
            for (int i = 0; i < pages.size(); i++) {
                int estimate = Math.max(0, pages.get(i).height - BODY_HEIGHT);
                overflow.put("card-" + (i + 1), cardMeasurer.measureOverflow(
                        cardsHtml.get(i), CARD_WIDTH, CARD_HEIGHT, estimate));
            }
            boolean movedAny = false;
            for (int i = pages.size() - 1; i >= 0; i--) {
                int guard = 0;
                int overflowPx = overflow.get("card-" + (i + 1)).overflowPx();
                while (overflowPx > 2 && !pages.get(i).blocks.isEmpty() && guard++ < 20) {
                    Block last = pages.get(i).blocks.remove(pages.get(i).blocks.size() - 1);
                    pages.get(i).height -= last.height;
                    if (i + 1 >= pages.size()) {
                        pages.add(new Page());
                        reflowed.add(0);
                    }
                    Page next = pages.get(i + 1);
                    next.blocks.add(0, last);
                    next.height += last.height;
                    reflowed.set(i, reflowed.get(i) + 1);
                    movedAny = true;
                    overflowPx = Math.max(0, pages.get(i).height - BODY_HEIGHT);
                }
            }
            if (!movedAny) {
                break;
            }
        }

        // 最终卡片 + 校验记录（QA 报告）
        List<String> cards = new ArrayList<>();
        List<CardQa> qa = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String html = renderCard(t, pages.get(i), i + 1, pages.size(), layout);
            cards.add(html);
            int estimate = Math.max(0, pages.get(i).height - BODY_HEIGHT);
            CardMeasurer.OverflowResult result = cardMeasurer.measureOverflow(
                    html, CARD_WIDTH, CARD_HEIGHT, estimate);
            qa.add(new CardQa(i + 1, result.overflowPx(), reflowed.get(i), result.measured()));
        }
        log.info("Xiaohongshu carousel rendered: cards={}, mode={}, layout={}",
                cards.size(), publishMode, layout);
        return new CarouselResult(cards, qa);
    }

    /**
     * Render a preview page embedding every carousel card via iframes.
     * Open {@code index.html} in a browser to review the whole deck, then
     * screenshot/export each card at 1080×1440.
     */
    public String renderCarouselPreview(String title, List<String> cards) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>")
                .append("<title>").append(escapeHtml(title == null ? "" : title)).append(" - 小红书卡片预览</title>")
                .append("<style>")
                .append("body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:#f0f1f3;")
                .append("margin:0;padding:40px 16px;}")
                .append("h1{font-size:22px;text-align:center;color:#1f1f1f;}")
                .append("p.tip{text-align:center;color:#888;font-size:14px;margin:8px 0 32px;}")
                .append(".frame{width:810px;height:1080px;margin:28px auto;overflow:hidden;position:relative;")
                .append("border:1px solid #ddd;border-radius:10px;box-shadow:0 4px 16px rgba(0,0,0,0.12);}")
                .append(".frame iframe{transform:scale(0.75);transform-origin:0 0;border:0;display:block;}")
                .append("</style></head><body>")
                .append("<h1>").append(escapeHtml(title == null ? "" : title)).append("</h1>")
                .append("<p class=\"tip\">共 ").append(cards.size())
                .append(" 张卡片，1080×1440。打开 card-XX.html 后截图，或用浏览器导出 PNG。</p>");
        for (int i = 0; i < cards.size(); i++) {
            html.append("<div class=\"frame\"><iframe src=\"").append(String.format(Locale.ROOT, "card-%02d.html", i + 1))
                    .append("\" width=\"1080\" height=\"1440\"></iframe></div>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ───────────────────────────── 分页与分块 ─────────────────────────────

    private static final class Block {
        String htmlTag;
        String rawHtml;
        int height;

        Block(String tag, String raw, int height) {
            this.htmlTag = tag;
            this.rawHtml = raw;
            this.height = height;
        }
    }

    private static final class Page {
        String title = "";
        String coverUrl = "";
        List<Block> blocks = new ArrayList<>();
        int height = 0;
        boolean imageCard;
        String imageUrl = "";
    }

    private List<Block> splitBlocks(String html) {
        List<Block> blocks = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return blocks;
        }
        Matcher matcher = TOP_LEVEL_BLOCK.matcher(html);
        int lastEnd = 0;
        while (matcher.find()) {
            String raw = matcher.group();
            String tag = matcher.group(1);
            if (tag == null) {
                if (raw.startsWith("<hr")) {
                    tag = "hr";
                } else if (raw.startsWith("<img")) {
                    tag = "img";
                } else {
                    tag = "p";
                }
            }
            blocks.add(new Block(tag, raw, estimateHeight(tag, raw)));
            lastEnd = matcher.end();
        }
        // Any loose text (should not happen with our parser) is wrapped as a paragraph.
        if (lastEnd < html.length() && !html.substring(lastEnd).trim().isEmpty()) {
            blocks.add(new Block("p", "<p>" + escapeHtml(html.substring(lastEnd).trim()) + "</p>", 100));
        }
        return blocks;
    }

    private int estimateHeight(String tag, String raw) {
        String text = TAG_STRIP.matcher(raw).replaceAll("");
        int chars = text.length();
        int lines = Math.max(1, (int) Math.ceil(chars * 1.0 / CHARS_PER_LINE));
        switch (tag) {
            case "h1": return 80;
            case "h2": return 72;
            case "h3": return 64;
            case "h4": return 58;
            case "h5":
            case "h6": return 52;
            case "ul":
            case "ol": {
                int items = countOccurrences(raw, LIST_ITEM);
                return 24 + items * 60;
            }
            case "blockquote": return 72 + lines * LINE_HEIGHT;
            case "pre": {
                int codeLines = text.split("\n", -1).length;
                return 72 + codeLines * 38;
            }
            case "hr": return 60;
            case "img": return 584; // image block + margins
            default: return 24 + lines * LINE_HEIGHT;
        }
    }

    private List<Block> splitOversizedBlock(Block block) {
        List<Block> chunks = new ArrayList<>();
        String tag = block.htmlTag;
        String text = TAG_STRIP.matcher(block.rawHtml).replaceAll("").trim();
        if (("p".equals(tag) || "blockquote".equals(tag)) && !text.isEmpty()) {
            List<String> parts = chunkText(text, PARAGRAPH_CHUNK);
            for (String part : parts) {
                String raw = "<p>" + escapeHtml(part) + "</p>";
                chunks.add(new Block("p", raw, estimateHeight("p", raw)));
            }
            return chunks;
        }
        if ("pre".equals(tag) && !text.isEmpty()) {
            String[] codeLines = text.split("\n", -1);
            StringBuilder group = new StringBuilder();
            int count = 0;
            for (String codeLine : codeLines) {
                group.append(codeLine).append('\n');
                count++;
                if (count >= 20) {
                    chunks.add(new Block("pre", "<pre><code>" + escapeHtml(group.toString().trim())
                            + "</code></pre>", 72 + 20 * 38));
                    group.setLength(0);
                    count = 0;
                }
            }
            if (!group.isEmpty()) {
                chunks.add(new Block("pre", "<pre><code>" + escapeHtml(group.toString().trim())
                        + "</code></pre>", 72 + count * 38));
            }
            return chunks;
        }
        // Fallback: keep the block as-is; the card will hide overflow.
        return List.of(block);
    }

    private List<String> chunkText(String text, int maxLen) {
        List<String> parts = new ArrayList<>();
        // Prefer sentence boundaries for nicer breaks.
        Matcher matcher = Pattern.compile(".{1," + maxLen + "}(?=[。！？!?；;\\s]|$)", Pattern.DOTALL).matcher(text);
        while (matcher.find()) {
            String part = matcher.group().trim();
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            parts.add(text);
        }
        return parts;
    }

    // ───────────────────────────── 卡片渲染 ─────────────────────────────

    private String renderCard(Theme t, Page page, int pageNo, int total, String layout) {
        String layoutName = layout == null || layout.isBlank() ? "classic" : layout;
        StringBuilder blocksHtml = new StringBuilder();
        if (page.imageCard) {
            blocksHtml.append("<div class=\"img-wrap\"><img src=\"").append(escapeAttr(page.imageUrl))
                    .append("\" alt=\"card\"/></div>");
        }
        for (Block block : page.blocks) {
            if (block == null || block.rawHtml == null || block.rawHtml.isBlank()) {
                continue;
            }
            if (block.rawHtml.startsWith("<img")) {
                blocksHtml.append("<div class=\"img-wrap\">").append(block.rawHtml).append("</div>");
            } else {
                blocksHtml.append(block.rawHtml);
            }
        }

        // hero 版式：首卡封面大图置顶；classic 版式标题在下，封面作为普通配图
        String heroMedia = "";
        if ("hero".equals(layoutName) && pageNo == 1 && !page.coverUrl.isBlank()) {
            heroMedia = "<div class=\"hero-media\"><img src=\"" + escapeAttr(page.coverUrl)
                    + "\" alt=\"cover\"/></div>";
        }
        String heroTitle = (!page.title.isBlank() && pageNo == 1)
                ? "<div class=\"hero\"><h1>" + escapeHtml(page.title) + "</h1></div>" : "";

        StringBuilder dots = new StringBuilder();
        for (int i = 1; i <= Math.min(total, 9); i++) {
            dots.append("<i class=\"").append(i == pageNo ? "on" : "").append("\"></i>");
        }

        String pageTitle = escapeHtml(page.title.isBlank() ? "ContentOps" : page.title);
        String titleTag = String.format(Locale.ROOT, "%02d/%02d · ", pageNo, total) + pageTitle;
        return CARD_TEMPLATE
                .replace("{{WIDTH}}", String.valueOf(CARD_WIDTH))
                .replace("{{HEIGHT}}", String.valueOf(CARD_HEIGHT))
                .replace("{{PADDING}}", String.valueOf(CARD_PADDING))
                .replace("{{CSS_VARS}}", t.cssVars())
                .replace("{{THEME}}", t.getCode())
                .replace("{{LAYOUT}}", layoutName)
                .replace("{{TITLE}}", titleTag)
                .replace("{{PAGE_NO}}", String.format(Locale.ROOT, "%02d/%02d", pageNo, total))
                .replace("{{HERO_MEDIA}}", heroMedia)
                .replace("{{HERO_TITLE}}", heroTitle)
                .replace("{{BLOCKS}}", blocksHtml.toString())
                .replace("{{DOTS}}", dots.toString());
    }

    // ───────────────────────────── 图片提取 ─────────────────────────────

    private String findCoverUrl(List<Map<String, Object>> images) {
        if (images == null) {
            return "";
        }
        for (Map<String, Object> img : images) {
            String purpose = String.valueOf(img.get("purpose"));
            if (purpose.contains("封面")) {
                String url = firstNonBlank(String.valueOf(img.get("url")), String.valueOf(img.get("imageUrl")));
                if (!url.isBlank() && !"null".equals(url)) {
                    return url;
                }
            }
        }
        return "";
    }

    private List<String> findInlineImageUrls(List<Map<String, Object>> images) {
        List<String> urls = new ArrayList<>();
        if (images == null) {
            return urls;
        }
        for (Map<String, Object> img : images) {
            String purpose = String.valueOf(img.get("purpose"));
            if (purpose.contains("封面")) {
                continue;
            }
            String url = firstNonBlank(String.valueOf(img.get("url")), String.valueOf(img.get("imageUrl")));
            if (!url.isBlank() && !"null".equals(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private String imageHtml(String url) {
        return "<img src=\"" + escapeAttr(url) + "\" alt=\"image\"/>";
    }

    private static Page currentPage(List<Page> pages) {
        return pages.get(pages.size() - 1);
    }

    private static Page newPage(List<Page> pages) {
        Page page = new Page();
        pages.add(page);
        return page;
    }

    // ───────────────────────────── 多尺寸画板 + 封面/封面对 ─────────────────────────────

    /**
     * 多尺寸画板：小红书轮播卡由 {@link #renderXiaohongshuCarousel} 负责；
     * 这里补充公众号封面对（21:9 头图 + 1:1 分享卡）与抖音/B站 16:9 封面，
     * 同一份内容用同一主题渲染，视觉保持一致。
     */
    public enum Artboard {
        WECHAT_HEADER_21_9("wechat-21-9", "公众号头图 21:9", 2100, 900),
        WECHAT_SQUARE_1_1("wechat-1-1", "公众号分享卡 1:1", 1080, 1080),
        DOUYIN_16_9("douyin-16-9", "抖音/B站封面 16:9", 1920, 1080);

        final String code;
        final String displayName;
        final int width;
        final int height;
        final int padding;
        final int titlePx;
        final int bodyPx;
        final int accentPx;

        Artboard(String code, String displayName, int width, int height) {
            this.code = code;
            this.displayName = displayName;
            this.width = width;
            this.height = height;
            this.padding = (int) Math.round(width * 0.075);
            double scale = Math.sqrt(width / 1080.0);
            this.titlePx = (int) Math.round(64 * scale);
            this.bodyPx = (int) Math.round(30 * scale);
            this.accentPx = Math.max(8, (int) Math.round(10 * scale));
        }
    }

    /** 公众号封面对：同一主题渲染的 21:9 头图 + 1:1 分享卡。 */
    public record CoverPair(String wideHtml, String squareHtml) {
    }

    /**
     * 渲染公众号封面对（21:9 头图 + 1:1 分享卡），视觉一致。
     */
    public CoverPair renderWechatCoverPair(String title, String markdown,
                                           String coverImageUrl, String theme) {
        return new CoverPair(
                renderCover(title, markdown, coverImageUrl, Artboard.WECHAT_HEADER_21_9, theme),
                renderCover(title, markdown, coverImageUrl, Artboard.WECHAT_SQUARE_1_1, theme));
    }

    /**
     * 渲染抖音 / B站 16:9 封面。
     */
    public String renderDouyinCover(String title, String markdown,
                                    String coverImageUrl, String theme) {
        return renderCover(title, markdown, coverImageUrl, Artboard.DOUYIN_16_9, theme);
    }

    /**
     * 渲染单张封面（通用画板）。宽画板（21:9）用图文左右布局，其余单列布局。
     */
    public String renderCover(String title, String markdown, String coverImageUrl,
                              Artboard board, String theme) {
        Theme t = Theme.from(theme);
        String safeTitle = escapeHtml(title == null ? "" : title);
        String excerpt = escapeHtml(extractExcerpt(markdown == null ? "" : markdown, 140));
        String cover = coverImageUrl == null || coverImageUrl.isBlank()
                ? "" : escapeAttr(coverImageUrl);
        boolean wide = board == Artboard.WECHAT_HEADER_21_9;

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"head\"><span class=\"brand\">ContentOps</span>")
                .append("<span class=\"tag\">").append(board.displayName).append("</span></div>\n");
        body.append("<div class=\"layout").append(wide ? " wide" : "").append("\">\n");
        body.append("<div class=\"text\">\n")
                .append("<h1>").append(safeTitle.isBlank() ? "未命名作品" : safeTitle).append("</h1>\n");
        if (!excerpt.isBlank()) {
            body.append("<p class=\"excerpt\">").append(excerpt).append("</p>\n");
        }
        body.append("</div>\n");
        if (!cover.isBlank()) {
            body.append("<div class=\"media\"><img src=\"").append(cover).append("\" alt=\"cover\"/></div>\n");
        }
        body.append("</div>\n");
        body.append("<div class=\"foot\"><span>社交平台封面</span><span class=\"page\">ContentOps · 1/1</span></div>\n");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=").append(board.width).append("\"/>")
                .append("<title>").append(safeTitle).append(" · ").append(board.displayName).append("</title>")
                .append("<style>").append(coverCss(t, board, wide)).append("</style></head><body>")
                .append("<div class=\"poster\">").append(body).append("</div></body></html>");
        return html.toString();
    }

    /**
     * 封面对预览页：把 21:9 与 1:1 两块并排展示（浏览器打开后截图导出）。
     */
    public String renderCoverPairPreview(String title, CoverPair pair) {
        return renderBoardPreview(title, pair.wideHtml(), pair.squareHtml(),
                "wechat/cover-wide-21x9.html", "wechat/cover-square-1x1.html");
    }

    private String renderBoardPreview(String title, String firstHtml, String secondHtml,
                                      String firstSrc, String secondSrc) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>")
                .append("<title>").append(escapeHtml(title == null ? "" : title)).append(" · 封面预览</title>")
                .append("<style>")
                .append("body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:#f0f1f3;")
                .append("margin:0;padding:40px 16px;}")
                .append("h1{font-size:22px;text-align:center;color:#1f1f1f;}")
                .append("p.tip{text-align:center;color:#888;font-size:14px;margin:8px 0 32px;}")
                .append(".row{display:flex;flex-wrap:wrap;gap:28px;justify-content:center;align-items:center;}")
                .append(".frame{overflow:hidden;position:relative;border:1px solid #ddd;border-radius:10px;")
                .append("box-shadow:0 4px 16px rgba(0,0,0,0.12);background:#fff;}")
                .append("</style></head><body>")
                .append("<h1>").append(escapeHtml(title == null ? "" : title)).append(" · 封面对</h1>")
                .append("<p class=\"tip\">同一主题渲染，视觉一致；打开单张 HTML 后截图导出。</p>")
                .append("<div class=\"row\">");
        appendPreviewFrame(html, firstHtml, firstSrc);
        appendPreviewFrame(html, secondHtml, secondSrc);
        html.append("</div></body></html>");
        return html.toString();
    }

    private void appendPreviewFrame(StringBuilder html, String content, String src) {
        // 简化：直接内联第一块内容 DOM 的尺寸信息，用 iframe 引用真实文件保证一致渲染
        html.append("<div class=\"frame\"><iframe src=\"").append(escapeAttr(src))
                .append("\" style=\"border:0;display:block;\"></iframe></div>");
    }

    private String coverCss(Theme t, Artboard b, boolean wide) {
        int brandPx = Math.max(18, b.titlePx / 2);
        int gap = (int) Math.round(b.padding * 0.5);
        int mediaHeight = (int) Math.round(b.height * 0.42);
        return "*{margin:0;padding:0;box-sizing:border-box;}"
                + "html,body{width:" + b.width + "px;height:" + b.height + "px;}"
                + "body{font-family:'PingFang SC','Hiragino Sans GB','Microsoft YaHei',-apple-system,sans-serif;"
                + "background:" + t.bg + ";color:" + t.text + ";}"
                + ".poster{width:" + b.width + "px;height:" + b.height + "px;padding:" + b.padding + "px;"
                + "display:flex;flex-direction:column;background:" + t.bg + ";overflow:hidden;}"
                + ".head{display:flex;justify-content:space-between;align-items:center;"
                + "margin-bottom:" + gap + "px;}"
                + ".brand{font-size:" + brandPx + "px;font-weight:700;color:" + t.accent + ";}"
                + ".tag{font-size:" + Math.max(16, b.bodyPx / 2) + "px;color:" + t.muted + ";"
                + "border:2px solid " + t.line + ";border-radius:999px;padding:6px 18px;}"
                + ".layout{flex:1;display:grid;gap:" + gap + "px;align-content:center;}"
                + ".layout.wide{grid-template-columns:1.15fr 1fr;align-items:center;}"
                + ".text h1{font-size:" + b.titlePx + "px;line-height:1.35;font-weight:800;color:" + t.text
                + ";padding-bottom:" + gap + "px;border-bottom:" + b.accentPx + "px solid " + t.accent + ";}"
                + ".excerpt{font-size:" + b.bodyPx + "px;line-height:1.8;color:" + t.muted
                + ";margin-top:" + gap + "px;}"
                + ".media{display:flex;align-items:center;justify-content:center;}"
                + ".media img{width:100%;max-height:" + mediaHeight + "px;object-fit:cover;"
                + "border-radius:" + Math.max(10, b.accentPx) + "px;box-shadow:0 8px 28px rgba(0,0,0,0.12);}"
                + ".foot{display:flex;justify-content:space-between;align-items:center;"
                + "border-top:2px solid " + t.line + ";padding-top:" + Math.max(12, gap / 2) + "px;"
                + "font-size:" + Math.max(16, b.bodyPx / 2) + "px;color:" + t.muted + ";}"
                + ".foot .page{color:" + t.accent + ";font-weight:600;}";
    }

    /**
     * 从 Markdown 提取首段作为封面摘要（去掉标题/图片/行内标记）。
     */
    private String extractExcerpt(String markdown, int maxLen) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown
                .replaceAll("(?m)^#{1,6}\\s+.*$", "")
                .replaceAll("!\\[.*?]\\(.*?\\)", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("`", "")
                .replaceAll("(?m)^>\\s*", "")
                .replaceAll("(?m)^[-*]\\s+", "");
        String[] paragraphs = text.split("\\n\\s*\\n");
        String first = "";
        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                first = trimmed;
                break;
            }
        }
        first = first.replaceAll("\\s+", " ").trim();
        if (first.length() > maxLen) {
            first = first.substring(0, maxLen - 1) + "…";
        }
        return first;
    }

    private static int countOccurrences(String raw, Pattern pattern) {
        Matcher matcher = pattern.matcher(raw);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank() && !"null".equals(a)) {
            return a;
        }
        if (b != null && !b.isBlank() && !"null".equals(b)) {
            return b;
        }
        return "";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeAttr(String text) {
        return escapeHtml(text).replace("`", "&#96;");
    }

    // ───────────────────────────── 主题 ─────────────────────────────

    private enum Theme {
        XHS("#FFFFFF", "#FF2442", "#1F1F1F", "#9A9A9A", "#FFF0F3", "#FFD9DF"),
        FRESH("#FDFFF9", "#00B873", "#1F2937", "#94A3B8", "#EAF7EF", "#D8EEE2"),
        WARM("#FFF9F0", "#E8853B", "#3D3D3D", "#B08F6F", "#FBEDDC", "#F4E0C9"),
        MINIMAL("#FAFAF8", "#111111", "#333333", "#999999", "#EFEFEA", "#DDDDD6");

        final String bg;
        final String accent;
        final String text;
        final String muted;
        final String blockBg;
        final String line;

        Theme(String bg, String accent, String text, String muted, String blockBg, String line) {
            this.bg = bg;
            this.accent = accent;
            this.text = text;
            this.muted = muted;
            this.blockBg = blockBg;
            this.line = line;
        }

        String getCode() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** CSS 变量块（卡片模板 :root 使用，主题只换变量值） */
        String cssVars() {
            return "--bg:" + bg + ";--accent:" + accent + ";--text:" + text
                    + ";--muted:" + muted + ";--block-bg:" + blockBg + ";--line:" + line + ";";
        }

        static Theme from(String name) {
            if (name == null || name.isBlank()) {
                return XHS;
            }
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("warm") || n.contains("暖")) {
                return WARM;
            }
            if (n.contains("minimal") || n.contains("极简") || n.contains("黑白")) {
                return MINIMAL;
            }
            if (n.contains("fresh") || n.contains("清新") || n.contains("绿")) {
                return FRESH;
            }
            return XHS;
        }

        /**
         * 合集按类型绑定主题：同一作品放进不同类型合集，导出时自动切换视觉变体。
         */
        static Theme forType(String type) {
            if (type == null || type.isBlank()) {
                return XHS;
            }
            String t = type;
            if (t.contains("干货") || t.contains("职场") || t.contains("知识") || t.contains("教程")
                    || t.contains("测评") || t.contains("数据")) {
                return FRESH;
            }
            if (t.contains("情感") || t.contains("故事") || t.contains("生活") || t.contains("旅行")
                    || t.contains("阅读") || t.contains("读书")) {
                return WARM;
            }
            if (t.contains("种草") || t.contains("好物") || t.contains("消费") || t.contains("穿搭")) {
                return XHS;
            }
            if (t.contains("成长") || t.contains("思考") || t.contains("方法论")) {
                return MINIMAL;
            }
            return XHS;
        }
    }
}
