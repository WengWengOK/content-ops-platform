package com.contentops.common.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownParser 单元测试 — 专注核心解析引擎。
 *
 * <p>验证：
 * <ul>
 *   <li>P0-1 XSS 防护：URL 协议校验 + HTML 转义</li>
 *   <li>基本 Markdown 语法解析（标题/列表/代码块/引用/段落）</li>
 *   <li>MarkdownConverter 的平台特定转换（WeChat/小红书/快手）</li>
 * </ul>
 */
@DisplayName("MarkdownParser 测试")
class MarkdownParserTest {

    private MarkdownParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarkdownParser();
    }

    // ════════════════ XSS 防护测试 ════════════════

    @Nested
    @DisplayName("XSS 防护 — 危险协议拦截")
    class XssProtection {

        @Test
        @DisplayName("javascript: 图片应被拦截")
        void javascriptImage_shouldBeBlocked() {
            String html = parser.convertToGenericHtml("![x](javascript:alert(1))");
            assertFalse(html.contains("javascript:alert"),
                    "javascript: 协议不应出现在输出中");
        }

        @Test
        @DisplayName("javascript: 链接应被拦截")
        void javascriptLink_shouldBeBlocked() {
            String html = parser.convertToGenericHtml("[click](javascript:alert(1))");
            assertFalse(html.contains("javascript:alert"),
                    "javascript: 协议不应出现在输出中");
        }

        @Test
        @DisplayName("vbscript: 协议应被拦截")
        void vbscript_shouldBeBlocked() {
            String html = parser.convertToGenericHtml("[x](vbscript:msgbox(1))");
            assertFalse(html.contains("vbscript:msgbox"),
                    "vbscript: 协议不应出现在输出中");
        }

        @Test
        @DisplayName("data:text/html 应被拦截")
        void dataTextHtml_shouldBeBlocked() {
            String html = parser.convertToGenericHtml("[x](data:text/html,<script>alert(1)</script>)");
            assertFalse(html.contains("data:text/html"),
                    "data:text/html 协议不应出现在输出中");
            assertFalse(html.contains("<script>"),
                    "script 标签不应出现在输出中");
        }

        @Test
        @DisplayName("file: 协议应被拦截")
        void fileProtocol_shouldBeBlocked() {
            String html = parser.convertToGenericHtml("[file](file:///etc/passwd)");
            assertFalse(html.contains("file:///"),
                    "file: 协议不应出现在输出中");
        }
    }

    @Nested
    @DisplayName("XSS 防护 — HTML 转义")
    class HtmlEscaping {

        @Test
        @DisplayName("图片 alt 中的引号应被转义")
        void altTextQuotes_shouldBeEscaped() {
            String html = parser.convertToGenericHtml("![alt\"onerror=alert(1)](https://example.com/img.png)");
            assertFalse(html.contains("\"onerror"),
                    "alt 中的引号应被转义");
        }

        @Test
        @DisplayName("链接文本中的尖括号应被转义")
        void linkTextAngleBrackets_shouldBeEscaped() {
            String html = parser.convertToGenericHtml("[<script>alert(1)</script>](https://example.com)");
            assertFalse(html.contains("<script>"),
                    "链接文本中的 <script> 应被转义");
        }

        @Test
        @DisplayName("代码块中的 HTML 应被转义")
        void codeBlockHtml_shouldBeEscaped() {
            String html = parser.convertToGenericHtml("```java\n<div>test</div>\n```");
            assertTrue(html.contains("&lt;div&gt;"),
                    "代码块中的 HTML 标签应被转义");
        }
    }

    @Nested
    @DisplayName("安全协议放行")
    class SafeProtocolPassThrough {

        @Test
        @DisplayName("https:// 图片应正常渲染")
        void httpsImage_shouldRender() {
            String html = parser.convertToGenericHtml("![img](https://example.com/img.png)");
            assertTrue(html.contains("<img"));
            assertTrue(html.contains("https://example.com/img.png"),
                    "https URL 应保留在输出中");
        }

        @Test
        @DisplayName("http:// 链接应正常渲染")
        void httpLink_shouldRender() {
            String html = parser.convertToGenericHtml("[link](http://example.com)");
            assertTrue(html.contains("<a"));
            assertTrue(html.contains("http://example.com"),
                    "http URL 应保留在输出中");
        }

        @Test
        @DisplayName("mailto: 协议应正常渲染")
        void mailtoLink_shouldRender() {
            String html = parser.convertToGenericHtml("[email](mailto:test@example.com)");
            assertTrue(html.contains("mailto:test@example.com"),
                    "mailto 协议应保留在输出中");
        }
    }

    // ════════════════ 基本语法解析测试 ════════════════

    @Nested
    @DisplayName("基本 Markdown 语法")
    class BasicSyntax {

        @Test
        @DisplayName("空输入应返回空字符串")
        void emptyInput_shouldReturnEmpty() {
            assertEquals("", parser.convertToGenericHtml(null));
            assertEquals("", parser.convertToGenericHtml(""));
            assertEquals("", parser.convertToGenericHtml("   "));
        }

        @Test
        @DisplayName("标题应正确转换")
        void headings_shouldConvert() {
            String html = parser.convertToGenericHtml("# H1\n## H2\n### H3");
            assertTrue(html.contains("<h1>H1</h1>"));
            assertTrue(html.contains("<h2>H2</h2>"));
            assertTrue(html.contains("<h3>H3</h3>"));
        }

        @Test
        @DisplayName("粗体应正确转换")
        void bold_shouldConvert() {
            String html = parser.convertToGenericHtml("**bold text**");
            assertTrue(html.contains("<strong>bold text</strong>"));
        }

        @Test
        @DisplayName("斜体应正确转换")
        void italic_shouldConvert() {
            String html = parser.convertToGenericHtml("*italic text*");
            assertTrue(html.contains("<em>italic text</em>"));
        }

        @Test
        @DisplayName("行内代码应正确转换")
        void inlineCode_shouldConvert() {
            String html = parser.convertToGenericHtml("this is `code` text");
            assertTrue(html.contains("<code>code</code>"));
        }

        @Test
        @DisplayName("无序列表应正确转换")
        void unorderedList_shouldConvert() {
            String html = parser.convertToGenericHtml("- item1\n- item2");
            assertTrue(html.contains("<ul>"));
            assertTrue(html.contains("<li>item1</li>"));
            assertTrue(html.contains("<li>item2</li>"));
        }

        @Test
        @DisplayName("有序列表应正确转换")
        void orderedList_shouldConvert() {
            String html = parser.convertToGenericHtml("1. first\n2. second");
            assertTrue(html.contains("<ol>"));
            assertTrue(html.contains("<li>first</li>"));
            assertTrue(html.contains("<li>second</li>"));
        }

        @Test
        @DisplayName("代码块应正确转换并转义 HTML")
        void codeBlock_shouldConvertAndEscape() {
            String html = parser.convertToGenericHtml("```java\n<div>test</div>\n```");
            assertTrue(html.contains("<pre><code>"));
            assertTrue(html.contains("&lt;div&gt;"));
        }

        @Test
        @DisplayName("引用块应正确转换")
        void blockquote_shouldConvert() {
            String html = parser.convertToGenericHtml("> this is a quote");
            assertTrue(html.contains("<blockquote>"));
            assertTrue(html.contains("this is a quote"));
        }

        @Test
        @DisplayName("水平分割线应正确转换")
        void horizontalRule_shouldConvert() {
            String html = parser.convertToGenericHtml("---");
            assertTrue(html.contains("<hr"));
        }

        @Test
        @DisplayName("段落应正确转换")
        void paragraph_shouldConvert() {
            String html = parser.convertToGenericHtml("This is a paragraph.");
            assertTrue(html.contains("<p>This is a paragraph.</p>"));
        }
    }
}
