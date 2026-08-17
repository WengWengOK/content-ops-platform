package com.contentops.common.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownConverter 单元测试。
 *
 * <p>重点验证 P0-1 XSS 漏洞修复：
 * <ul>
 *   <li>URL 协议白名单校验（http/https 通过，javascript/vbscript 拦截）</li>
 *   <li>HTML 实体转义（引号、尖括号）</li>
 *   <li>图片 alt 文本和链接文本转义</li>
 *   <li>正常 Markdown 语法转换</li>
 * </ul>
 */
@DisplayName("MarkdownConverter 测试")
class MarkdownConverterTest {

    private MarkdownConverter converter;

    @BeforeEach
    void setUp() {
        // MarkdownConverter now delegates core parsing to MarkdownParser (P2-13 refactor)
        converter = new MarkdownConverter(new MarkdownParser());
    }

    // ════════════════ XSS 防护测试 ════════════════

    @Nested
    @DisplayName("XSS 防护 — URL 协议校验")
    class XssProtocolValidation {

        @Test
        @DisplayName("javascript: 协议的图片应被拦截")
        void javascriptImageProtocol_shouldBeBlocked() {
            String markdown = "![x](javascript:alert(document.cookie))";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("javascript:alert"),
                    "javascript: 协议不应出现在 HTML 输出中");
            assertFalse(html.contains("alert(document.cookie)"),
                    "XSS payload 不应出现在 HTML 输出中");
        }

        @Test
        @DisplayName("javascript: 协议的链接应被拦截")
        void javascriptLinkProtocol_shouldBeBlocked() {
            String markdown = "[点击](javascript:alert(1))";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("javascript:alert"),
                    "javascript: 协议不应出现在 HTML 输出中");
        }

        @Test
        @DisplayName("vbscript: 协议应被拦截")
        void vbscriptProtocol_shouldBeBlocked() {
            String markdown = "[点击](vbscript:msgbox('xss'))";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("vbscript:msgbox"),
                    "vbscript: 协议不应出现在 HTML 输出中");
        }

        @Test
        @DisplayName("data: 非图片协议应被拦截")
        void dataNonImageProtocol_shouldBeBlocked() {
            String markdown = "[点击](data:text/html,<script>alert(1)</script>)";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("data:text/html"),
                    "data:text/html 协议不应出现在 HTML 输出中");
            assertFalse(html.contains("<script>"),
                    "script 标签不应出现在 HTML 输出中");
        }

        @Test
        @DisplayName("file: 协议应被拦截")
        void fileProtocol_shouldBeBlocked() {
            String markdown = "[文件](file:///etc/passwd)";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("file:///"),
                    "file: 协议不应出现在 HTML 输出中");
        }
    }

    @Nested
    @DisplayName("XSS 防护 — HTML 实体转义")
    class XssHtmlEscaping {

        @Test
        @DisplayName("图片 alt 文本中的双引号应被转义")
        void altTextQuotes_shouldBeEscaped() {
            String markdown = "![alt\"onerror=alert(1)](https://example.com/img.png)";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("\"onerror"),
                    "alt 文本中的引号应被转义，防止属性注入");
        }

        @Test
        @DisplayName("链接文本中的尖括号应被转义")
        void linkTextAngleBrackets_shouldBeEscaped() {
            String markdown = "[<script>alert(1)</script>](https://example.com)";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("<script>"),
                    "链接文本中的 <script> 标签应被转义");
        }

        @Test
        @DisplayName("URL 中的双引号应被转义")
        void urlQuotes_shouldBeEscaped() {
            String markdown = "[link](https://example.com/path\"onclick=alert(1))";
            String html = converter.convertToHtml(markdown);
            assertFalse(html.contains("\"onclick"),
                    "URL 中的引号应被转义，防止属性注入");
        }
    }

    @Nested
    @DisplayName("XSS 防护 — 安全协议放行")
    class SafeProtocolPassThrough {

        @Test
        @DisplayName("https:// 协议的图片应正常输出")
        void httpsImage_shouldRender() {
            String markdown = "![图片](https://example.com/img.png)";
            String html = converter.convertToHtml(markdown);
            assertTrue(html.contains("<img"), "应生成 img 标签");
            assertTrue(html.contains("https://example.com/img.png"),
                    "https URL 应保留在输出中");
        }

        @Test
        @DisplayName("http:// 协议的链接应正常输出")
        void httpLink_shouldRender() {
            String markdown = "[链接](http://example.com)";
            String html = converter.convertToHtml(markdown);
            assertTrue(html.contains("<a"), "应生成 a 标签");
            assertTrue(html.contains("http://example.com"),
                    "http URL 应保留在输出中");
        }

        @Test
        @DisplayName("mailto: 协议应正常输出")
        void mailtoLink_shouldRender() {
            String markdown = "[邮箱](mailto:test@example.com)";
            String html = converter.convertToHtml(markdown);
            assertTrue(html.contains("mailto:test@example.com"),
                    "mailto 协议应保留在输出中");
        }
    }

    // ════════════════ 基本 Markdown 转换测试 ════════════════

    @Nested
    @DisplayName("基本 Markdown 语法转换")
    class BasicMarkdownConversion {

        @Test
        @DisplayName("空字符串应返回空")
        void emptyInput_shouldReturnEmpty() {
            assertEquals("", converter.convertToHtml(""));
            assertEquals("", converter.convertToHtml(null));
            assertEquals("", converter.convertToHtml("   "));
        }

        @Test
        @DisplayName("标题应正确转换")
        void headings_shouldConvert() {
            String html = converter.convertToHtml("# 标题1\n## 标题2");
            assertTrue(html.contains("<h1>标题1</h1>"));
            assertTrue(html.contains("<h2>标题2</h2>"));
        }

        @Test
        @DisplayName("粗体应正确转换")
        void bold_shouldConvert() {
            String html = converter.convertToHtml("**粗体文本**");
            assertTrue(html.contains("<strong>粗体文本</strong>"));
        }

        @Test
        @DisplayName("行内代码应正确转换")
        void inlineCode_shouldConvert() {
            String html = converter.convertToHtml("这是 `code` 文本");
            assertTrue(html.contains("<code>code</code>"));
        }

        @Test
        @DisplayName("无序列表应正确转换")
        void unorderedList_shouldConvert() {
            String html = converter.convertToHtml("- 项目1\n- 项目2");
            assertTrue(html.contains("<ul>"));
            assertTrue(html.contains("<li>项目1</li>"));
            assertTrue(html.contains("<li>项目2</li>"));
        }

        @Test
        @DisplayName("有序列表应正确转换")
        void orderedList_shouldConvert() {
            String html = converter.convertToHtml("1. 第一\n2. 第二");
            assertTrue(html.contains("<ol>"));
            assertTrue(html.contains("<li>第一</li>"));
            assertTrue(html.contains("<li>第二</li>"));
        }

        @Test
        @DisplayName("代码块应正确转换并转义 HTML")
        void codeBlock_shouldConvertAndEscape() {
            String html = converter.convertToHtml("```java\n<div>test</div>\n```");
            assertTrue(html.contains("<pre><code>"));
            assertTrue(html.contains("&lt;div&gt;"),
                    "代码块内的 HTML 标签应被转义");
        }

        @Test
        @DisplayName("引用块应正确转换")
        void blockquote_shouldConvert() {
            String html = converter.convertToHtml("> 这是引用");
            assertTrue(html.contains("<blockquote>"));
            assertTrue(html.contains("这是引用"));
        }
    }

    // ════════════════ 平台特定转换测试 ════════════════

    @Nested
    @DisplayName("平台特定格式转换")
    class PlatformSpecificConversion {

        @Test
        @DisplayName("公众号应添加内联样式")
        void wechat_shouldHaveInlineStyles() {
            String html = converter.convert("# 标题", "公众号");
            assertTrue(html.contains("style="), "公众号 HTML 应包含内联样式");
            assertTrue(html.contains("07C160"), "公众号应使用微信绿色");
        }

        @Test
        @DisplayName("小红书应输出纯文本")
        void xiaohongshu_shouldBePlainText() {
            String html = converter.convert("**粗体**", "小红书");
            assertFalse(html.contains("<strong>"), "小红书不应包含 HTML 标签");
            assertTrue(html.contains("「粗体」"), "小红书粗体应转换为括号");
        }

        @Test
        @DisplayName("快手应截断超长文本")
        void kuaishou_shouldTruncate() {
            String longText = "这是一段很长的文本".repeat(20);
            String result = converter.convert(longText, "快手");
            assertTrue(result.length() <= 103, "快手文本应被截断到100字+省略号");
            assertTrue(result.endsWith("..."), "截断后应以省略号结尾");
        }
    }
}
