package com.contentops.common.rag;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.rag.DocumentChunker.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档摄入流水线（RAG 全链路升级）。
 *
 * <p>完整的 RAG 摄入链路：<b>解析 → 清洗 → 分块 → 向量化 → 存储</b>。
 * 支持 PDF、Word（.docx）、Markdown、TXT 等格式，并提取文档元数据（标题、来源、格式、创建时间等）。
 *
 * <p>各阶段说明：
 * <ul>
 *   <li><b>解析</b>：依据文件扩展名选择 {@link DocumentParser} 实现。PDF/DOCX 使用纯 JDK
 *       实现（PDF 解压 FlateDecode 流并提取文本算子；DOCX 解析 OOXML 的 {@code word/document.xml}），
 *       无需引入 PDFBox/POI 等额外依赖；Markdown/TXT 直接按文本读取。</li>
 *   <li><b>清洗</b>：可选地剥离 HTML 标签、折叠多余空白。</li>
 *   <li><b>分块</b>：委托 {@link DocumentChunker} 按配置策略切分。</li>
 *   <li><b>向量化 + 存储</b>：每个分块写入 {@link KnowledgeBaseService}（PGVector + BGE 嵌入），
 *       并在元数据中写入 {@code chunk_id} 以便混合检索跨路匹配。</li>
 *   <li><b>关键词索引</b>：每个分块同步注册到 {@link HybridSearchService} 的 BM25 内存索引。</li>
 * </ul>
 *
 * <p><b>降级策略：</b>PGVector 不可用时向量入库失败但不阻断——分块仍会进入 BM25 关键词索引，
 * 检索可退化为纯关键词模式；{@code failFast=false}（默认）时整体异常返回失败结果而非抛出。
 *
 * <p><b>PDF 解析限制：</b>纯 JDK 实现对未压缩/FlateDecode 压缩的文本流有效，对使用其它
 * 编码或加密的 PDF 可能提取不全；如需更高保真度，可引入 Apache PDFBox 并替换 {@link PdfParser}。
 *
 * @see RagProperties.Ingestion
 */
@Slf4j
@Component
public class DocumentIngestionPipeline {

    private final RagProperties properties;
    private final DocumentChunker chunker;
    private final KnowledgeBaseService knowledgeBaseService;
    private final HybridSearchService hybridSearchService;

    /**
     * 构造摄入流水线。
     *
     * @param properties          RAG 配置
     * @param chunker             分块器
     * @param knowledgeBaseService 知识库服务（向量存储）
     * @param hybridSearchService 混合检索服务（BM25 索引）
     */
    public DocumentIngestionPipeline(RagProperties properties, DocumentChunker chunker,
                                     KnowledgeBaseService knowledgeBaseService,
                                     HybridSearchService hybridSearchService) {
        this.properties = properties;
        this.chunker = chunker;
        this.knowledgeBaseService = knowledgeBaseService;
        this.hybridSearchService = hybridSearchService;
        log.info("DocumentIngestionPipeline initialized: supportedFormats={}, maxFileSizeBytes={}, failFast={}",
                properties.getIngestion().getSupportedFormats(), properties.getIngestion().getMaxFileSizeBytes(),
                properties.getIngestion().isFailFast());
    }

    /**
     * 文档元数据。
     *
     * @param title     标题
     * @param author    作者
     * @param source    来源（文件名/URL 等）
     * @param format    格式（扩展名）
     * @param createdAt 创建时间
     * @param extra     额外元数据
     */
    public record DocumentMetadata(String title, String author, String source, String format,
                                   Instant createdAt, Map<String, String> extra) {
    }

    /**
     * 摄入结果。
     *
     * @param documentId 文档标识
     * @param chunkCount 成功入库的分块数
     * @param success    是否成功
     * @param message    说明信息
     * @param metadata   提取的文档元数据
     */
    public record IngestionResult(String documentId, int chunkCount, boolean success,
                                  String message, DocumentMetadata metadata) {
    }

    /**
     * 解析后的文档（纯文本 + 元数据）。
     *
     * @param text     解析得到的纯文本
     * @param metadata 文档元数据
     */
    public record ParsedDocument(String text, DocumentMetadata metadata) {
    }

    /**
     * 文档解析器接口（密封）。具体实现：{@link TxtParser}、{@link MarkdownTextParser}、
     * {@link PdfParser}、{@link DocxParser}。
     */
    private sealed interface DocumentParser permits TxtParser, MarkdownTextParser, PdfParser, DocxParser {
        /** 解析文档为纯文本与元数据。 */
        ParsedDocument parse(byte[] content, String fileName);
    }

    /** 纯文本解析器。 */
    private record TxtParser() implements DocumentParser {
        @Override
        public ParsedDocument parse(byte[] content, String fileName) {
            String text = new String(content, StandardCharsets.UTF_8);
            return new ParsedDocument(text, buildMetadata(text, fileName, "txt"));
        }
    }

    /** Markdown 解析器：读取为文本并轻量剥离 Markdown 语法标记。 */
    private record MarkdownTextParser() implements DocumentParser {
        @Override
        public ParsedDocument parse(byte[] content, String fileName) {
            String raw = new String(content, StandardCharsets.UTF_8);
            String text = stripMarkdown(raw);
            return new ParsedDocument(text, buildMetadata(raw, fileName, "md"));
        }
    }

    /** PDF 解析器：纯 JDK 实现，提取 FlateDecode 文本流中的文本算子。 */
    private record PdfParser() implements DocumentParser {
        @Override
        public ParsedDocument parse(byte[] content, String fileName) {
            String text = extractPdfText(content);
            return new ParsedDocument(text, buildMetadata(text, fileName, "pdf"));
        }
    }

    /** Word（.docx）解析器：解析 OOXML 的 word/document.xml 提取文本。 */
    private record DocxParser() implements DocumentParser {
        @Override
        public ParsedDocument parse(byte[] content, String fileName) {
            String text = extractDocxText(content);
            return new ParsedDocument(text, buildMetadata(text, fileName, "docx"));
        }
    }

    /**
     * 摄入文档主入口：解析 → 清洗 → 分块 → 向量化 → 存储。
     *
     * @param content  文档字节内容
     * @param fileName 文件名（用于推断格式与元数据）
     * @param metadata 调用方提供的额外元数据（type/niche/agent/workflowId 等），可为 null
     * @return 摄入结果
     */
    public IngestionResult ingest(byte[] content, String fileName, Map<String, String> metadata) {
        String name = fileName == null ? "unknown.txt" : fileName;
        String documentId = UUID.randomUUID().toString();
        try {
            RagProperties.Ingestion cfg = properties.getIngestion();
            if (content == null || content.length == 0) {
                return failure(documentId, "empty content", name);
            }
            if (content.length > cfg.getMaxFileSizeBytes()) {
                return failure(documentId, "file size " + content.length
                        + " exceeds max " + cfg.getMaxFileSizeBytes(), name);
            }

            String format = detectFormat(name);
            DocumentParser parser = selectParser(format, cfg.getSupportedFormats());
            ParsedDocument parsed = parser.parse(content, name);
            String text = cleanText(parsed.text());
            if (text == null || text.isBlank()) {
                return failure(documentId, "no extractable text after parsing/cleaning", name);
            }

            Map<String, String> base = buildBaseMetadata(documentId, parsed.metadata(), metadata);
            List<Chunk> chunks = chunker.chunk(text, base);
            if (chunks.isEmpty()) {
                return failure(documentId, "chunking produced no chunks", name);
            }

            // 向量化 + 存储（best-effort），并注册 BM25 索引
            int stored = 0;
            for (Chunk chunk : chunks) {
                Map<String, String> storeMeta = new HashMap<>(chunk.metadata());
                storeMeta.put("chunk_id", chunk.id());
                storeMeta.put("docId", documentId);
                if (knowledgeBaseService.ingest(chunk.content(), storeMeta)) {
                    stored++;
                }
            }
            // 关键词索引独立于向量库，始终注册以支持降级检索
            hybridSearchService.indexChunks(chunks);

            String message = stored == chunks.size()
                    ? "ingested all " + chunks.size() + " chunks"
                    : "ingested " + stored + "/" + chunks.size() + " chunks into vector store; "
                            + chunks.size() + " indexed for keyword search";
            log.info("Document ingested: docId={}, file='{}', chunks={}, vectorStored={}, keywordIndexed={}",
                    documentId, name, chunks.size(), stored, chunks.size());
            return new IngestionResult(documentId, stored, true, message, parsed.metadata());
        } catch (Exception e) {
            log.error("Document ingestion failed: docId={}, file='{}'", documentId, name, e);
            if (properties.getIngestion().isFailFast()) {
                throw new RuntimeException("Document ingestion failed: " + e.getMessage(), e);
            }
            return failure(documentId, "ingestion error: " + e.getMessage(), name);
        }
    }

    /**
     * 摄入纯文本（便捷方法，等价于按 TXT 处理）。
     *
     * @param text     纯文本
     * @param fileName 文件名（建议 .txt）
     * @param metadata 额外元数据
     * @return 摄入结果
     */
    public IngestionResult ingestText(String text, String fileName, Map<String, String> metadata) {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        String name = (fileName == null || fileName.isBlank()) ? "text.txt" : fileName;
        return ingest(bytes, name, metadata);
    }

    /**
     * 摄入本地文件。
     *
     * @param path     文件路径
     * @param metadata 额外元数据
     * @return 摄入结果
     * @throws IOException 文件读取失败
     */
    public IngestionResult ingestFile(Path path, Map<String, String> metadata) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return ingest(bytes, path.getFileName().toString(), metadata);
    }

    // ──────────────────── 解析器选择与文本清洗 ────────────────────

    /** 推断文件格式（小写扩展名）。 */
    private static String detectFormat(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1) : "txt";
        return ext.toLowerCase();
    }

    /** 依据格式选择解析器；不在支持列表中的格式回退为纯文本解析器并告警。 */
    private DocumentParser selectParser(String format, List<String> supported) {
        boolean supportedFormat = supported == null || supported.isEmpty()
                || supported.contains(format);
        if (!supportedFormat) {
            log.warn("Format '{}' not in supportedFormats {}, falling back to TxtParser", format, supported);
            return new TxtParser();
        }
        return switch (format) {
            case "txt" -> new TxtParser();
            case "md", "markdown" -> new MarkdownTextParser();
            case "pdf" -> new PdfParser();
            case "docx" -> new DocxParser();
            default -> {
                log.warn("Unknown format '{}', falling back to TxtParser", format);
                yield new TxtParser();
            }
        };
    }

    /** 清洗文本：可选剥离 HTML 标签、折叠多余空白。 */
    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        String t = text;
        if (properties.getIngestion().isCleanHtml()) {
            t = t.replaceAll("<[^>]+>", " ");
        }
        if (properties.getIngestion().isCleanWhitespace()) {
            t = t.replaceAll("[ \\t\\f]+", " ")
                    .replaceAll("\\r\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .replaceAll(" *\\n *", "\n")
                    .trim();
        }
        return t;
    }

    /** 构建基础元数据：合并解析元数据与调用方元数据。 */
    private static Map<String, String> buildBaseMetadata(String documentId,
                                                         DocumentMetadata docMeta,
                                                         Map<String, String> callerMetadata) {
        Map<String, String> base = new HashMap<>();
        base.put("docId", documentId);
        if (docMeta != null) {
            if (docMeta.title() != null && !docMeta.title().isBlank()) {
                base.put("title", docMeta.title());
            }
            if (docMeta.source() != null) {
                base.put("source", docMeta.source());
            }
            if (docMeta.format() != null) {
                base.put("format", docMeta.format());
            }
            if (docMeta.createdAt() != null) {
                base.put("createdAt", docMeta.createdAt().toString());
            }
            if (docMeta.author() != null && !docMeta.author().isBlank()) {
                base.put("author", docMeta.author());
            }
        }
        if (callerMetadata != null) {
            base.putAll(callerMetadata);
        }
        return base;
    }

    /** 构造失败结果与最小元数据。 */
    private static IngestionResult failure(String documentId, String message, String fileName) {
        DocumentMetadata meta = new DocumentMetadata(fileName, "", fileName, detectFormat(fileName), Instant.now(), Map.of());
        return new IngestionResult(documentId, 0, false, message, meta);
    }

    /** 从文本启发式提取标题（首个非空、去除 Markdown 标记的行）。 */
    private static String extractTitle(String text, String fileName) {
        if (text == null || text.isBlank()) {
            return fileName;
        }
        for (String line : text.split("\n", 10)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed.replaceAll("^#+\\s*", "").replaceAll("[*_`]", "");
            }
        }
        return fileName;
    }

    /** 构建文档元数据。 */
    private static DocumentMetadata buildMetadata(String text, String fileName, String format) {
        String title = extractTitle(text, fileName);
        return new DocumentMetadata(title, "", fileName, format, Instant.now(), Map.of());
    }

    // ──────────────────── Markdown 文本剥离 ────────────────────

    /** 轻量剥离 Markdown 语法标记，保留纯文本。 */
    private static String stripMarkdown(String md) {
        if (md == null || md.isEmpty()) {
            return "";
        }
        String t = md;
        // 代码块 → 保留内容但去掉围栏
        t = t.replaceAll("(?s)```.*?\\n", "");
        t = t.replaceAll("```", "");
        // 图片 ![alt](url) → alt
        t = t.replaceAll("!\\[(.*?)]\\([^)]*\\)", "$1");
        // 链接 [text](url) → text
        t = t.replaceAll("\\[(.*?)]\\([^)]*\\)", "$1");
        // 标题井号、强调、行内代码
        t = t.replaceAll("(?m)^#{1,6}\\s*", "");
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        t = t.replaceAll("__(.+?)__", "$1");
        t = t.replaceAll("\\*(.+?)\\*", "$1");
        t = t.replaceAll("_(.+?)_", "$1");
        t = t.replaceAll("`([^`]+)`", "$1");
        t = t.replaceAll("~~(.+?)~~", "$1");
        // 引用与列表标记
        t = t.replaceAll("(?m)^>\\s?", "");
        t = t.replaceAll("(?m)^[-*+]\\s+", "");
        t = t.replaceAll("(?m)^\\d+\\.\\s+", "");
        return t;
    }

    // ──────────────────── PDF 文本提取（纯 JDK，best-effort） ────────────────────

    private static final Pattern STREAM_PATTERN =
            Pattern.compile("stream\\r?\\n(.*?)\\r?\\nendstream", Pattern.DOTALL);
    private static final Pattern TJ_PATTERN = Pattern.compile("\\((.*?)\\)\\s*Tj", Pattern.DOTALL);
    private static final Pattern TJ_ARRAY_PATTERN = Pattern.compile("\\[(.*?)]\\s*TJ", Pattern.DOTALL);
    private static final Pattern STRING_IN_ARRAY = Pattern.compile("\\((.*?)\\)", Pattern.DOTALL);

    /** 提取 PDF 文本：解压 FlateDecode 流并提取 Tj/TJ 文本算子。 */
    private static String extractPdfText(byte[] bytes) {
        // PDF 内部为字节导向，用 ISO-8859-1 可无损往返以支持正则匹配
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();

        Matcher streamMatcher = STREAM_PATTERN.matcher(raw);
        while (streamMatcher.find()) {
            byte[] payload = streamMatcher.group(1).getBytes(StandardCharsets.ISO_8859_1);
            String inflated = inflate(payload);
            if (!inflated.isEmpty()) {
                out.append(extractTextOperators(inflated)).append('\n');
            }
        }
        // 未压缩内容也尝试提取
        out.append(extractTextOperators(raw));
        return out.toString();
    }

    /** 尝试 zlib（nowrap=false）与 raw deflate（nowrap=true）两种方式解压。 */
    private static String inflate(byte[] data) {
        String result = inflate(data, false);
        if (result.isEmpty()) {
            result = inflate(data, true);
        }
        return result;
    }

    private static String inflate(byte[] data, boolean nowrap) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            Inflater inflater = new Inflater(nowrap);
            inflater.setInput(data);
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                if (n > 0) {
                    baos.write(buf, 0, n);
                }
            }
            inflater.end();
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 PDF 内容流中提取 Tj/TJ 文本算子的字符串。 */
    private static String extractTextOperators(String content) {
        StringBuilder out = new StringBuilder();
        Matcher m = TJ_PATTERN.matcher(content);
        while (m.find()) {
            out.append(unescapePdfString(m.group(1)));
        }
        Matcher mArr = TJ_ARRAY_PATTERN.matcher(content);
        while (mArr.find()) {
            Matcher inner = STRING_IN_ARRAY.matcher(mArr.group(1));
            while (inner.find()) {
                out.append(unescapePdfString(inner.group(1)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** 反转义 PDF 字符串字面量。 */
    private static String unescapePdfString(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                // 把八进制转义提取到独立方法，避免 switch 表达式内嵌套 for 循环
                // 导致 Java 21 字节码 VerifyError（Inconsistent stackmap frames）
                if (next >= '0' && next <= '7') {
                    int[] pos = {i};
                    out.append(parseOctalEscape(s, next, pos));
                    i = pos[0];
                } else {
                    out.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case '(', ')', '\\' -> next;
                        default -> next;
                    });
                }
            } else if (c == '(' || c == ')') {
                out.append(c);
            } else if (c >= 0x20) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 解析 PDF 八进制转义序列（最多三位），返回对应字符。
     * 从 unescapePdfString 提取，避免 switch 表达式内嵌套循环导致字节码 VerifyError。
     *
     * @param s     原始字符串
     * @param first 第一个八进制字符
     * @param pos   pos[0] 入参为当前索引，出参为消费后的索引（可能前移 0~2 位）
     */
    private static char parseOctalEscape(String s, char first, int[] pos) {
        StringBuilder oct = new StringBuilder().append(first);
        for (int k = 0; k < 2 && pos[0] + 1 < s.length(); k++) {
            char oc = s.charAt(pos[0] + 1);
            if (oc >= '0' && oc <= '7') {
                oct.append(oc);
                pos[0]++;
            } else {
                break;
            }
        }
        return (char) Integer.parseInt(oct.toString(), 8);
    }

    // ──────────────────── DOCX 文本提取（纯 JDK，StAX） ────────────────────

    /**
     * 提取 .docx 文本：定位 ZIP 中的 word/document.xml，
     * 用 StAX 读取字符事件并在段落/换行处插入换行符。
     */
    private static String extractDocxText(byte[] bytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    byte[] data = zis.readAllBytes();
                    return readXmlText(new ByteArrayInputStream(data));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read .docx archive", e);
        } catch (Exception e) {
            log.warn("Failed to parse .docx document.xml", e);
        }
        return "";
    }

    /** 用 StAX 读取 XML 文本内容，在 p/tr/br 处插入换行。 */
    private static String readXmlText(InputStream in) throws Exception {
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
        // 出于安全考虑，禁用外部实体与 DTD
        factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        javax.xml.stream.XMLStreamReader reader = factory.createXMLStreamReader(in);
        StringBuilder out = new StringBuilder();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == javax.xml.stream.XMLStreamConstants.CHARACTERS) {
                    String text = reader.getText();
                    if (text != null && !text.isBlank()) {
                        out.append(text);
                    }
                } else if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("p".equals(name) || "tr".equals(name) || "br".equals(name)) {
                        out.append('\n');
                    }
                }
            }
        } finally {
            reader.close();
        }
        return out.toString();
    }
}
