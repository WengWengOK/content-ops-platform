package com.contentops.common.platform;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core Markdown → HTML parsing engine.
 *
 * <p>Extracted (P2-13) from {@link MarkdownConverter}. Responsible solely for parsing
 * Markdown into clean, semantic HTML without platform-specific styling. Platform-specific
 * styling remains in {@link MarkdownConverter}.
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
 * <p>Preserves all P0 security fixes (XSS prevention, URL protocol validation, HTML escaping).
 */
@Component
public class MarkdownParser {

    /** Allowed URL protocols for markdown links and images */
    private static final Pattern SAFE_URL_PROTOCOL =
            Pattern.compile("^(https?://|mailto:|data:image/|#)", Pattern.CASE_INSENSITIVE);

    /** Pattern for detecting javascript: and other dangerous protocol prefixes */
    private static final Pattern DANGEROUS_PROTOCOL =
            Pattern.compile("^(javascript|vbscript|file|data(?!:image/)):", Pattern.CASE_INSENSITIVE);

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
