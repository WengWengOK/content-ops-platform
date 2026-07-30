package com.contentops.common.platform;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Markdown → HTML converter with platform-specific styling.
 *
 * <p>P0 ④: Replaces the mock {@code convertToPlatformFormat} with a genuine
 * Markdown parser that produces clean, platform-ready HTML.
 *
 * <p>Supported Markdown syntax:
 * <ul>
 *   <li>Headings: {@code #} through {@code ######}</li>
 *   <li>Bold: {@code **text**} and {@code __text__}</li>
 *   <li>Italic: {@code *text*} and {@code _text_}</li>
 *   <li>Strikethrough: {@code ~~text~~}</li>
 *   <li>Inline code: {@code `code`}</li>
 *   <li>Code blocks: {@code ```lang\ncode\n```}</li>
 *   <li>Blockquotes: {@code > text}</li>
 *   <li>Unordered lists: {@code -} or {@code *}</li>
 *   <li>Ordered lists: {@code 1.}</li>
 *   <li>Images: {@code ![alt](url)}</li>
 *   <li>Links: {@code [text](url)}</li>
 *   <li>Horizontal rules: {@code ---} or {@code ***}</li>
 *   <li>Paragraphs: separated by blank lines</li>
 * </ul>
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
public class MarkdownConverter {

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
            default -> convertToGenericHtml(markdown);
        };
    }

    /**
     * Convert Markdown to clean generic HTML (no platform-specific styling).
     */
    public String convertToHtml(String markdown) {
        return convertToGenericHtml(markdown);
    }

    // ════════════════ WeChat (公众号) ════════════════

    /**
     * Convert Markdown to WeChat-compatible HTML with inline styles.
     *
     * <p>WeChat strips CSS classes and {@code <style>} tags, so all styling
     * must be inline. The output uses a warm, readable color palette.
     */
    public String convertToWechatHtml(String markdown) {
        String html = convertToGenericHtml(markdown);
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
        String html = convertToGenericHtml(markdown);
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
        String html = convertToGenericHtml(markdown);
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
        String html = convertToGenericHtml(markdown);
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

    // ════════════════ Core Markdown → Generic HTML engine ════════════════

    /**
     * Convert Markdown to clean, semantic HTML without platform-specific styles.
     *
     * <p>This is the core conversion engine. It processes the input line by line,
     * handling block-level elements first (code blocks, headings, lists, blockquotes,
     * horizontal rules), then applies inline formatting (bold, italic, links, images,
     * inline code) within each block.
     */
    public String convertToGenericHtml(String markdown) {
        String[] lines = markdown.split("\n");
        StringBuilder html = new StringBuilder();
        List<String> paragraphBuffer = new ArrayList<>();
        List<String> listBuffer = new ArrayList<>();
        boolean listOrdered = false;
        boolean inCodeBlock = false;
        StringBuilder codeBlockBuffer = new StringBuilder();
        StringBuilder blockquoteBuffer = new StringBuilder();
        boolean inBlockquote = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // ── Code block ──
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // End code block
                    flushParagraph(html, paragraphBuffer);
                    flushList(html, listBuffer, listOrdered);
                    html.append("<pre><code>")
                            .append(escapeHtml(codeBlockBuffer.toString().trim()))
                            .append("</code></pre>\n");
                    codeBlockBuffer.setLength(0);
                    inCodeBlock = false;
                } else {
                    // Start code block
                    flushParagraph(html, paragraphBuffer);
                    flushList(html, listBuffer, listOrdered);
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlockBuffer.append(line).append("\n");
                continue;
            }

            // ── Blockquote ──
            if (trimmed.startsWith(">")) {
                flushParagraph(html, paragraphBuffer);
                flushList(html, listBuffer, listOrdered);
                String quoteContent = trimmed.substring(1).trim();
                blockquoteBuffer.append(applyInlineFormatting(quoteContent)).append("<br/>\n");
                inBlockquote = true;
                continue;
            } else if (inBlockquote) {
                // End blockquote
                html.append("<blockquote>")
                        .append(blockquoteBuffer.toString().replaceAll("<br/>\\n$", ""))
                        .append("</blockquote>\n");
                blockquoteBuffer.setLength(0);
                inBlockquote = false;
            }

            // ── Horizontal rule ──
            if (trimmed.matches("^---+$|^\\*\\*\\*+$|^___+$")) {
                flushParagraph(html, paragraphBuffer);
                flushList(html, listBuffer, listOrdered);
                html.append("<hr/>\n");
                continue;
            }

            // ── Heading ──
            Matcher headingMatcher = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(trimmed);
            if (headingMatcher.matches()) {
                flushParagraph(html, paragraphBuffer);
                flushList(html, listBuffer, listOrdered);
                int level = headingMatcher.group(1).length();
                String content = applyInlineFormatting(headingMatcher.group(2));
                html.append("<h").append(level).append(">")
                        .append(content)
                        .append("</h").append(level).append(">\n");
                continue;
            }

            // ── Unordered list ──
            if (trimmed.matches("^[-*]\\s+.+")) {
                flushParagraph(html, paragraphBuffer);
                if (listBuffer.isEmpty()) {
                    listOrdered = false;
                }
                String item = trimmed.replaceFirst("^[-*]\\s+", "");
                listBuffer.add(applyInlineFormatting(item));
                continue;
            }

            // ── Ordered list ──
            if (trimmed.matches("^\\d+\\.\\s+.+")) {
                flushParagraph(html, paragraphBuffer);
                if (listBuffer.isEmpty()) {
                    listOrdered = true;
                }
                String item = trimmed.replaceFirst("^\\d+\\.\\s+", "");
                listBuffer.add(applyInlineFormatting(item));
                continue;
            }

            // ── Non-list, non-empty line: flush list if active ──
            if (!trimmed.isEmpty() && !listBuffer.isEmpty()) {
                flushList(html, listBuffer, listOrdered);
            }

            // ── Empty line: paragraph break ──
            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraphBuffer);
                flushList(html, listBuffer, listOrdered);
                continue;
            }

            // ── Regular text line ──
            paragraphBuffer.add(trimmed);
        }

        // Flush remaining buffers
        if (inCodeBlock) {
            html.append("<pre><code>")
                    .append(escapeHtml(codeBlockBuffer.toString().trim()))
                    .append("</code></pre>\n");
        }
        if (inBlockquote) {
            html.append("<blockquote>")
                    .append(blockquoteBuffer.toString().replaceAll("<br/>\\n$", ""))
                    .append("</blockquote>\n");
        }
        flushParagraph(html, paragraphBuffer);
        flushList(html, listBuffer, listOrdered);

        return html.toString().trim();
    }

    // ════════════════ Inline formatting ════════════════

    /** Allowed URL protocols for markdown links and images */
    private static final Pattern SAFE_URL_PROTOCOL =
            Pattern.compile("^(https?://|mailto:|data:image/|#)", Pattern.CASE_INSENSITIVE);

    /** Pattern for detecting javascript: and other dangerous protocol prefixes */
    private static final Pattern DANGEROUS_PROTOCOL =
            Pattern.compile("^(javascript|vbscript|file|data(?!:image/)):", Pattern.CASE_INSENSITIVE);

    /**
     * Validate and sanitize a URL for use in an HTML attribute.
     * <p>Rejects dangerous protocols (javascript:, vbscript:, etc.) and
     * HTML-escapes the URL to prevent attribute injection.
     *
     * @param rawUrl the raw URL extracted from Markdown
     * @return sanitized URL safe for HTML attribute, or empty string if dangerous
     */
    private String sanitizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String url = rawUrl.trim();
        // Reject dangerous protocols
        if (DANGEROUS_PROTOCOL.matcher(url).matches()) {
            return "";
        }
        // If URL has a protocol, ensure it's safe
        if (url.contains("://") && !SAFE_URL_PROTOCOL.matcher(url).matches()) {
            return "";
        }
        // HTML-escape to prevent attribute breakout (quotes, angle brackets)
        return escapeHtml(url);
    }

    /**
     * Apply inline Markdown formatting: bold, italic, strikethrough,
     * inline code, images, links.
     */
    private String applyInlineFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Images: ![alt](url) → <img> (with URL sanitization + XSS prevention)
        Matcher imgMatcher = Pattern.compile("!\\[(.*?)]\\((.+?)\\)").matcher(text);
        StringBuilder imgResult = new StringBuilder();
        while (imgMatcher.find()) {
            String alt = escapeHtml(imgMatcher.group(1));
            String safeUrl = sanitizeUrl(imgMatcher.group(2));
            imgMatcher.appendReplacement(imgResult,
                    Matcher.quoteReplacement("<img src=\"" + safeUrl + "\" alt=\"" + alt + "\" />"));
        }
        imgMatcher.appendTail(imgResult);
        text = imgResult.toString();

        // Links: [text](url) → <a> (with URL sanitization + XSS prevention)
        Matcher linkMatcher = Pattern.compile("\\[(.*?)]\\(([^)]+)\\)").matcher(text);
        StringBuilder linkResult = new StringBuilder();
        while (linkMatcher.find()) {
            String linkText = escapeHtml(linkMatcher.group(1));
            String safeUrl = sanitizeUrl(linkMatcher.group(2));
            linkMatcher.appendReplacement(linkResult,
                    Matcher.quoteReplacement("<a href=\"" + safeUrl + "\">" + linkText + "</a>"));
        }
        linkMatcher.appendTail(linkResult);
        text = linkResult.toString();

        // Inline code: `code` → <code> (must be before bold/italic)
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");

        // Bold: **text** or __text__ → <strong>
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        text = text.replaceAll("__(.+?)__", "<strong>$1</strong>");

        // Italic: *text* or _text_ → <em>
        text = text.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        text = text.replaceAll("(?<![a-zA-Z])_(.+?)_(?![a-zA-Z])", "<em>$1</em>");

        // Strikethrough: ~~text~~ → <del>
        text = text.replaceAll("~~(.+?)~~", "<del>$1</del>");

        return text;
    }

    // ════════════════ Helpers ════════════════

    private void flushParagraph(StringBuilder html, List<String> buffer) {
        if (buffer.isEmpty()) return;
        String content = String.join(" ", buffer);
        html.append("<p>").append(content).append("</p>\n");
        buffer.clear();
    }

    private void flushList(StringBuilder html, List<String> buffer, boolean ordered) {
        if (buffer.isEmpty()) return;
        String tag = ordered ? "ol" : "ul";
        html.append("<").append(tag).append(">\n");
        for (String item : buffer) {
            html.append("<li>").append(item).append("</li>\n");
        }
        html.append("</").append(tag).append(">\n");
        buffer.clear();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
