package com.contentops.common.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Real Markdown → HTML converter with platform-specific styling.
 *
 * <p>Refactored (P2-13): the core Markdown parsing engine now lives in
 * {@link MarkdownParser}. This class remains the public facade, keeping all
 * platform-specific styling logic and preserving the original public API.
 *
 * <p>P0 ④: Replaces the mock {@code convertToPlatformFormat} with a genuine
 * Markdown parser that produces clean, platform-ready HTML.
 *
 * <p>Platform-specific styling:
 * <ul>
 *   <li><b>公众号 (WeChat)</b>: inline styles on every element (WeChat strips
 *       CSS classes and {@code <style>} tags). Uses warm color palette,
 *       centered images, styled blockquotes and code blocks.</li>
 *   <li><b>头条</b>: similar to WeChat but with slightly different spacing.</li>
 *   <li><b>小红书/抖音/快手</b>: strips to plain text with emoji-friendly formatting.</li>
 *   <li><b>知乎/B站</b>: clean semantic HTML without inline styles.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MarkdownConverter {

    private final MarkdownParser markdownParser;

    // ════════════════ Public API ════════════════

    /**
     * Convert Markdown to platform-specific HTML.
     *
     * @param markdown the Markdown source text
     * @param platform target platform name (公众号, 头条, 小红书, 知乎, B站, 抖音, 快手)
     * @return converted HTML (or plain text for text-only platforms)
     */
    public String convert(String markdown, String platform) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        return switch (platform) {
            case "公众号" -> convertToWechatHtml(markdown);
            case "头条" -> convertToToutiaoHtml(markdown);
            case "小红书" -> convertToXiaohongshuText(markdown);
            case "知乎" -> convertToZhihuHtml(markdown);
            case "B站" -> convertToBilibiliHtml(markdown);
            case "抖音" -> convertToPlainText(markdown, 1000);
            case "快手" -> convertToPlainText(markdown, 100);
            default -> markdownParser.convertToGenericHtml(markdown);
        };
    }

    /**
     * Convert Markdown to clean generic HTML (no platform-specific styling).
     */
    public String convertToHtml(String markdown) {
        return markdownParser.convertToGenericHtml(markdown);
    }

    // ════════════════ WeChat (公众号) ════════════════

    /**
     * Convert Markdown to WeChat-compatible HTML with inline styles.
     *
     * <p>WeChat strips CSS classes and {@code <style>} tags, so all styling
     * must be inline. The output uses a warm, readable color palette.
     */
    public String convertToWechatHtml(String markdown) {
        String html = markdownParser.convertToGenericHtml(markdown);
        // Apply WeChat inline styles
        html = applyWechatStyles(html);
        return html;
    }

    private String applyWechatStyles(String html) {
        // Section wrapper for consistent styling
        html = html.replaceAll("<h1>(.*?)</h1>",
                "<h1 style=\"font-size:22px;font-weight:bold;color:#333;margin:24px 0 12px;padding-bottom:8px;border-bottom:2px solid #07C160;\">$1</h1>");
        html = html.replaceAll("<h2>(.*?)</h2>",
                "<h2 style=\"font-size:19px;font-weight:bold;color:#333;margin:22px 0 10px;padding-left:10px;border-left:4px solid #07C160;\">$1</h2>");
        html = html.replaceAll("<h3>(.*?)</h3>",
                "<h3 style=\"font-size:17px;font-weight:bold;color:#333;margin:20px 0 8px;\">$1</h3>");
        html = html.replaceAll("<h4>(.*?)</h4>",
                "<h4 style=\"font-size:16px;font-weight:bold;color:#555;margin:18px 0 6px;\">$1</h4>");
        html = html.replaceAll("<h5>(.*?)</h5>",
                "<h5 style=\"font-size:15px;font-weight:bold;color:#555;margin:16px 0 6px;\">$1</h5>");
        html = html.replaceAll("<h6>(.*?)</h6>",
                "<h6 style=\"font-size:14px;font-weight:bold;color:#777;margin:14px 0 4px;\">$1</h6>");
        html = html.replaceAll("<p>(.*?)</p>",
                "<p style=\"font-size:15px;line-height:1.8;color:#3f3f3f;margin:12px 0;letter-spacing:0.5px;\">$1</p>");
        html = html.replaceAll("<blockquote>(.*?)</blockquote>",
                "<blockquote style=\"margin:16px 0;padding:12px 16px;background:#f7f7f7;border-left:4px solid #07C160;border-radius:4px;color:#666;font-size:14px;line-height:1.7;\">$1</blockquote>");
        html = html.replaceAll("<ul>",
                "<ul style=\"padding-left:20px;margin:12px 0;color:#3f3f3f;font-size:15px;line-height:1.8;\">");
        html = html.replaceAll("<ol>",
                "<ol style=\"padding-left:20px;margin:12px 0;color:#3f3f3f;font-size:15px;line-height:1.8;\">");
        html = html.replaceAll("<li>(.*?)</li>",
                "<li style=\"margin:4px 0;\">$1</li>");
        html = html.replaceAll("<img\\s+([^>]*)>",
                "<img $1 style=\"max-width:100%;border-radius:4px;margin:16px auto;display:block;box-shadow:0 2px 8px rgba(0,0,0,0.1);\" />");
        html = html.replaceAll("<a\\s+([^>]*)>(.*?)</a>",
                "<a $1 style=\"color:#07C160;text-decoration:none;border-bottom:1px solid #07C160;\">$2</a>");
        html = html.replaceAll("<pre><code>",
                "<pre style=\"background:#1e1e1e;color:#d4d4d4;padding:16px;border-radius:8px;overflow-x:auto;margin:16px 0;font-size:14px;line-height:1.6;\"><code>");
        html = html.replaceAll("<hr\\s*/?>",
                "<hr style=\"border:none;border-top:1px dashed #ccc;margin:24px 0;\" />");
        html = html.replaceAll("<strong>(.*?)</strong>",
                "<strong style=\"color:#07C160;\">$1</strong>");
        return html;
    }

    // ════════════════ Toutiao (头条) ════════════════

    public String convertToToutiaoHtml(String markdown) {
        String html = markdownParser.convertToGenericHtml(markdown);
        html = html.replaceAll("<h1>(.*?)</h1>",
                "<h1 style=\"font-size:24px;font-weight:bold;color:#222;margin:20px 0 10px;\">$1</h1>");
        html = html.replaceAll("<h2>(.*?)</h2>",
                "<h2 style=\"font-size:20px;font-weight:bold;color:#222;margin:18px 0 8px;\">$1</h2>");
        html = html.replaceAll("<h3>(.*?)</h3>",
                "<h3 style=\"font-size:18px;font-weight:bold;color:#333;margin:16px 0 6px;\">$1</h3>");
        html = html.replaceAll("<p>(.*?)</p>",
                "<p style=\"font-size:17px;line-height:1.75;color:#333;margin:14px 0;\">$1</p>");
        html = html.replaceAll("<blockquote>(.*?)</blockquote>",
                "<blockquote style=\"margin:14px 0;padding:10px 14px;background:#f5f5f5;border-left:3px solid #f04142;color:#666;font-size:15px;\">$1</blockquote>");
        html = html.replaceAll("<ul>",
                "<ul style=\"padding-left:20px;margin:12px 0;font-size:17px;line-height:1.75;\">");
        html = html.replaceAll("<ol>",
                "<ol style=\"padding-left:20px;margin:12px 0;font-size:17px;line-height:1.75;\">");
        html = html.replaceAll("<img\\s+([^>]*)>",
                "<img $1 style=\"max-width:100%;margin:14px auto;display:block;text-align:center;\" />");
        html = html.replaceAll("<strong>(.*?)</strong>",
                "<strong style=\"color:#f04142;\">$1</strong>");
        return html;
    }

    // ════════════════ Zhihu (知乎) ════════════════

    public String convertToZhihuHtml(String markdown) {
        String html = markdownParser.convertToGenericHtml(markdown);
        html = html.replaceAll("<p>(.*?)</p>",
                "<p style=\"line-height:1.7;margin:16px 0;\">$1</p>");
        html = html.replaceAll("<blockquote>(.*?)</blockquote>",
                "<blockquote style=\"margin:16px 0;padding:10px 16px;color:#666;border-left:3px solid #999;\">$1</blockquote>");
        html = html.replaceAll("<img\\s+([^>]*)>",
                "<img $1 style=\"max-width:100%;margin:12px 0;\" />");
        return html;
    }

    // ════════════════ Bilibili (B站) ════════════════

    public String convertToBilibiliHtml(String markdown) {
        String html = markdownParser.convertToGenericHtml(markdown);
        html = html.replaceAll("<p>(.*?)</p>",
                "<p style=\"line-height:1.8;margin:12px 0;font-size:15px;color:#222;\">$1</p>");
        html = html.replaceAll("<blockquote>(.*?)</blockquote>",
                "<blockquote style=\"margin:12px 0;padding:8px 12px;background:#f4f5f7;border-left:3px solid #00a1d6;color:#505050;\">$1</blockquote>");
        html = html.replaceAll("<strong>(.*?)</strong>",
                "<strong style=\"color:#00a1d6;\">$1</strong>");
        html = html.replaceAll("<img\\s+([^>]*)>",
                "<img $1 style=\"max-width:100%;margin:10px auto;display:block;\" />");
        return html;
    }

    // ════════════════ Xiaohongshu (小红书) — plain text + emoji ════════════════

    public String convertToXiaohongshuText(String markdown) {
        String text = markdown;
        // Convert headings to emoji-decorated lines
        text = text.replaceAll("(?m)^#{1,6}\\s+(.+)$", "✨ $1");
        // Convert bold to brackets
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "「$1」");
        text = text.replaceAll("__(.+?)__", "「$1」");
        // Convert italic
        text = text.replaceAll("\\*(.+?)\\*", "$1");
        text = text.replaceAll("_(.+?)_", "$1");
        // Convert strikethrough
        text = text.replaceAll("~~(.+?)~~", "~$1~");
        // Convert images to placeholders
        text = text.replaceAll("!\\[(.*?)]\\((.+?)\\)", "\n📸 [图片: $1]\n");
        // Convert links — keep text only
        text = text.replaceAll("\\[(.*?)]\\((.+?)\\)", "$1");
        // Convert blockquotes
        text = text.replaceAll("(?m)^>\\s*(.+)$", "💬 $1");
        // Convert list items
        text = text.replaceAll("(?m)^[-*]\\s+(.+)$", "• $1");
        text = text.replaceAll("(?m)^\\d+\\.\\s+(.+)$", "• $1");
        // Convert horizontal rules
        text = text.replaceAll("(?m)^---+$", "———————————");
        text = text.replaceAll("(?m)^\\*\\*\\*+$", "———————————");
        // Remove code block markers
        text = text.replaceAll("```\\w*", "\n");
        text = text.replaceAll("```", "\n");
        // Remove inline code backticks
        text = text.replaceAll("`(.+?)`", "$1");
        // Collapse multiple blank lines
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    // ════════════════ Plain text (抖音/快手) ════════════════

    public String convertToPlainText(String markdown, int maxLength) {
        String text = markdown;
        // Strip all Markdown syntax
        text = text.replaceAll("(?m)^#{1,6}\\s+", "");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("__(.+?)__", "$1");
        text = text.replaceAll("\\*(.+?)\\*", "$1");
        text = text.replaceAll("_(.+?)_", "$1");
        text = text.replaceAll("~~(.+?)~~", "$1");
        text = text.replaceAll("`(.+?)`", "$1");
        text = text.replaceAll("```\\w*", "");
        text = text.replaceAll("```", "");
        text = text.replaceAll("!\\[(.*?)]\\((.+?)\\)", "");
        text = text.replaceAll("\\[(.*?)]\\((.+?)\\)", "$1");
        text = text.replaceAll("(?m)^>\\s*", "");
        text = text.replaceAll("(?m)^[-*]\\s+", "");
        text = text.replaceAll("(?m)^\\d+\\.\\s+", "");
        text = text.replaceAll("(?m)^---+$", "");
        text = text.replaceAll("(?m)^\\*\\*\\*+$", "");
        // Collapse whitespace
        text = text.replaceAll("\\n{3,}", "\n\n").trim();

        if (maxLength > 0 && text.length() > maxLength) {
            text = text.substring(0, maxLength - 3) + "...";
        }
        return text;
    }
}
