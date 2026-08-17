package com.contentops.common.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分块增强器：为 {@link DocumentChunker} 产出的基础分块注入
 * <b>父子文档（parent-child documents）</b>关系与<b>标题层级元数据</b>。
 *
 * <h2>设计动机（面试洞察）</h2>
 *
 * <h3>1. 父子文档策略（Parent-Child Document Strategy）</h3>
 * <p>传统 RAG 按「检索粒度」与「上下文粒度」一致的方式切分，存在两难：
 * 切得太细则命中精确但缺乏上下文，切得太粗则上下文充足但检索精度下降。
 * 父子文档策略解耦二者——
 * <ul>
 *   <li><b>子分块（child chunk）</b>：尺寸小、用于精确检索命中；</li>
 *   <li><b>父分块（parent chunk）</b>：尺寸大（默认为子分块的
 *       {@value #PARENT_SIZE_MULTIPLIER} 倍），仅作为上下文存储，
 *       命中子分块后取回其父分块为生成提供完整语境。</li>
 * </ul>
 * 本组件接收 {@link DocumentChunker} 已切好的（子）分块，按源文本位置将连续子分块
 * 归组到父分块，并为每个子分块记录其所属父分块 ID 与父分块内容摘录。
 *
 * <h3>2. 标题层级元数据（Title Hierarchy Metadata）</h3>
 * <p>解析 Markdown 标题（{@code #}~{@code ######}）与中文法规文档标题
 * （{@code 第X章/节/条/款/项}），在分块元数据中保留从根到叶的标题路径，
 * 例如 {@code ["第一章 总则", "第二节 适用范围", "第三条"]}。检索命中后可据此
 * 还原分块在文档结构中的位置，支撑「按章节过滤」「溯源展示」等能力。
 *
 * <h3>3. 语义边界检测（Semantic Boundary Detection）</h3>
 * <p>在法规/技术文档的篇、章、节、条等结构边界处往往发生主题切换。
 * {@link #detectSemanticBoundary(String, String)} 通过「新标题出现」与
 * 「相邻文本词汇重合度骤降」两类信号判定主题漂移，可用于指导父分块在边界处对齐，
 * 避免将跨主题内容混入同一父分块。
 *
 * <h2>与既有组件的集成</h2>
 * <p>本组件只做「增强」而不重新切分：输入是 {@link DocumentChunker#chunk(String, Map)}
 * 的产出，输出是携带父子关系与标题路径的 {@link ParentChildChunk} 列表，
 * 子分块元数据被就地富化（写入 {@code parentChunkId}、{@code headingPath}、
 * {@code headingDepth}）。父分块尺寸、上下文窗口均由
 * {@link RagProperties.Chunking#getChunkSize()} / {@link RagProperties.Chunking#getChunkOverlap()}
 * 推导，保持与既有分块配置一致。
 *
 * <h2>Java 21 特性</h2>
 * <ul>
 *   <li>{@link ParentChildChunk}、{@link MarkdownHeading}、{@link LegalHeading}、
 *       {@link HeadingOccurrence}、{@link ChunkSpan} 等记录类型；</li>
 *   <li>密封接口 {@link HeadingMatch} 配合 {@code switch} 模式匹配（含 {@code null} 分支）；</li>
 *   <li>文本块、{@code Stream#toList()}、增强 {@code switch} 表达式。</li>
 * </ul>
 *
 * @see DocumentChunker
 * @see DocumentChunker.Chunk
 * @see RagProperties.Chunking
 */
@Slf4j
@Component
public class SemanticChunkerEnhancer {

    /** 父分块相对子分块 {@code chunkSize} 的放大倍数：父分块 = 子分块 × 该倍数。 */
    static final int PARENT_SIZE_MULTIPLIER = 4;

    /** 父分块内容摘录的最大字符数，超出部分截断以避免元数据过大。 */
    private static final int PARENT_EXCERPT_LIMIT = 2000;

    /** 语义边界检测：取前/后文本尾部/首部的字符窗口大小，用于词汇重合度比较。 */
    private static final int BOUNDARY_WINDOW = 200;

    /** 语义边界检测：相邻文本词汇 Jaccard 重合度低于该阈值即判定为主题切换。 */
    private static final double BOUNDARY_OVERLAP_THRESHOLD = 0.15;

    /**
     * Markdown 标题正则：{@code ^#{1,6}\s+(.+)$}（多行模式，行首 1~6 个 {@code #} 后接标题文本）。
     */
    private static final Pattern MARKDOWN_HEADING_PATTERN =
            Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);

    /**
     * 中文法规/技术文档标题正则：行首「第 + 中文数字/阿拉伯数字 + 结构字」。
     * <p>结构字及其层级见 {@link #LEGAL_LEVELS}，覆盖篇、编、章、节、条、款、项、目。
     */
    private static final Pattern LEGAL_HEADING_PATTERN =
            Pattern.compile("^第[一二三四五六七八九十百千零两0-9]+[章节条款款项编篇]",
                    Pattern.MULTILINE);

    /** 法规结构字 → 层级映射（数字越小层级越高）。 */
    private static final Map<Character, Integer> LEGAL_LEVELS = Map.of(
            '篇', 0, '编', 1, '章', 2, '节', 3,
            '条', 4, '款', 5, '项', 6, '目', 7);

    private final RagProperties properties;

    /**
     * 构造增强器。
     *
     * @param properties RAG 全链路配置，从中读取 {@link RagProperties.Chunking}
     *                   推导父分块尺寸与上下文窗口
     */
    public SemanticChunkerEnhancer(RagProperties properties) {
        this.properties = properties;
        RagProperties.Chunking c = properties.getChunking();
        log.info("SemanticChunkerEnhancer initialized: childChunkSize={}, parentChunkSize={}, chunkOverlap={}",
                c.getChunkSize(), c.getChunkSize() * PARENT_SIZE_MULTIPLIER, c.getChunkOverlap());
    }

    // ====================================================================
    // 公共数据类型
    // ====================================================================

    /**
     * 密封标题匹配接口：统一抽象 Markdown 标题与中文法规标题两类检测结果，
     * 供 {@code switch} 模式匹配按类型解构层级与文本。
     */
    public sealed interface HeadingMatch permits MarkdownHeading, LegalHeading {
        /** 标题文本。 */
        String text();

        /** 层级（数字越小层级越高；Markdown 为 1~6，法规为 0~7）。 */
        int level();
    }

    /** Markdown 标题（{@code #} 个数即层级）。 */
    public record MarkdownHeading(String text, int level) implements HeadingMatch {
    }

    /** 中文法规/技术文档标题（如「第三条」，层级由结构字决定）。 */
    public record LegalHeading(String text, int level) implements HeadingMatch {
    }

    /**
     * 父子分块增强结果。
     *
     * @param childChunk     子分块（检索命中单元），其 {@code metadata} 已被富化，
     *                       写入 {@code parentChunkId}、{@code headingPath}、{@code headingDepth}
     * @param parentChunkId  所属父分块 ID（同一父分块下的多个子分块共享同一 ID）
     * @param parentContent  父分块内容摘录（命中子分块后取回，为生成提供上下文）
     * @param headingPath    子分块所在位置的标题层级路径，从根到叶，
     *                       如 {@code ["第一章 总则", "第二节 适用范围", "第三条"]}
     */
    public record ParentChildChunk(DocumentChunker.Chunk childChunk,
                                   String parentChunkId,
                                   String parentContent,
                                   List<String> headingPath) {
    }

    // ====================================================================
    // 核心增强入口
    // ====================================================================

    /**
     * 对 {@link DocumentChunker} 产出的（子）分块进行父子文档归组与标题层级富化。
     *
     * <p>处理流程：
     * <ol>
     *   <li>在源文本中定位每个子分块的字符区间（顺序匹配，兼容重叠分块）；</li>
     *   <li>按源文本跨度将连续子分块归组到父分块——当累计内容达到父分块尺寸
     *       （{@code chunkSize × }{@link #PARENT_SIZE_MULTIPLIER}）或跨度超限时另起父分块；</li>
     *   <li>对每个父分组调用 {@link #extractParentContext(String, int, int)} 取父分块内容摘录；</li>
     *   <li>对每个子分块调用 {@link #parseHeadingPath(String, int)} 取标题层级路径；</li>
     *   <li>将父分块 ID 与标题路径写入子分块元数据，组装 {@link ParentChildChunk} 返回。</li>
     * </ol>
     *
     * <p>当源文本为空或子分块无法在源文本中定位时，仍返回包装结果（父信息与标题路径为空），
     * 不抛异常、不阻断主流程。
     *
     * @param chunks     {@link DocumentChunker#chunk(String, Map)} 的产出，作为子分块
     * @param sourceText 原始文档全文，用于定位分块位置与解析标题层级
     * @return 携带父子关系与标题路径的增强分块列表，顺序与输入一致
     */
    public List<ParentChildChunk> enhanceChunks(List<DocumentChunker.Chunk> chunks, String sourceText) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (sourceText == null || sourceText.isBlank()) {
            log.debug("sourceText is blank; wrapping {} chunks with empty parent context", chunks.size());
            return chunks.stream()
                    .map(c -> new ParentChildChunk(c, "", "", List.of()))
                    .toList();
        }

        RagProperties.Chunking cfg = properties.getChunking();
        int parentSize = Math.max(cfg.getChunkSize() * PARENT_SIZE_MULTIPLIER,
                cfg.getChunkSize() + cfg.getChunkOverlap());

        // 1. 定位每个子分块在源文本中的字符区间
        List<ChunkSpan> spans = locateChunks(chunks, sourceText);

        // 2. 按源文本跨度归组到父分块（两段式：先分组，再统一取父内容）
        List<List<Integer>> groups = new ArrayList<>();
        List<ChunkSpan> groupSpans = new ArrayList<>();
        List<String> groupIds = new ArrayList<>();

        List<Integer> currentGroup = new ArrayList<>();
        int groupStart = -1;
        int groupEnd = -1;
        int groupContentLen = 0;
        String currentParentId = null;
        int parentIdx = 0;

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.Chunk chunk = chunks.get(i);
            ChunkSpan span = spans.get(i);
            int start = span.start();
            int end = span.end();
            int contentLen = (chunk.content() != null) ? chunk.content().length() : 0;

            boolean exceed = groupContentLen + contentLen > parentSize
                    || (start >= 0 && groupStart >= 0 && (end - groupStart) > parentSize);

            if (currentParentId == null || exceed) {
                flushGroup(currentGroup, groupStart, groupEnd, currentParentId, groups, groupSpans, groupIds);
                currentGroup = new ArrayList<>();
                groupStart = start;
                groupEnd = end;
                groupContentLen = 0;
                parentIdx++;
                currentParentId = generateParentId(chunk, parentIdx);
            }

            currentGroup.add(i);
            if (start >= 0) {
                groupStart = (groupStart < 0) ? start : Math.min(groupStart, start);
            }
            if (end >= 0) {
                groupEnd = Math.max(groupEnd, end);
            }
            groupContentLen += contentLen;
        }
        flushGroup(currentGroup, groupStart, groupEnd, currentParentId, groups, groupSpans, groupIds);

        // 3. 组装结果：每组取父内容，每个子分块取标题路径并富化元数据
        List<ParentChildChunk> result = new ArrayList<>(chunks.size());
        for (int g = 0; g < groups.size(); g++) {
            List<Integer> childIndices = groups.get(g);
            ChunkSpan groupSpan = groupSpans.get(g);
            String parentId = groupIds.get(g);
            String parentContent = extractParentContext(sourceText, groupSpan.start(), groupSpan.end());

            for (int idx : childIndices) {
                DocumentChunker.Chunk chunk = chunks.get(idx);
                ChunkSpan span = spans.get(idx);
                List<String> headingPath = (span.start() >= 0)
                        ? parseHeadingPath(sourceText, span.start())
                        : List.of();

                Map<String, String> enriched = new LinkedHashMap<>();
                if (chunk.metadata() != null) {
                    enriched.putAll(chunk.metadata());
                }
                enriched.put("parentChunkId", parentId);
                enriched.put("headingPath", String.join(" > ", headingPath));
                enriched.put("headingDepth", String.valueOf(headingPath.size()));

                DocumentChunker.Chunk enrichedChild = new DocumentChunker.Chunk(
                        chunk.id(), chunk.content(), chunk.order(), enriched);
                result.add(new ParentChildChunk(enrichedChild, parentId, parentContent, headingPath));
            }
        }

        log.debug("Enhanced {} child chunks into {} parent groups", chunks.size(), groups.size());
        return result;
    }

    // ====================================================================
    // 标题层级解析
    // ====================================================================

    /**
     * 解析源文本在 {@code position} 处的标题层级路径。
     *
     * <p>扫描从文本开头到 {@code position} 的所有标题（Markdown 与中文法规标题），
     * 维护一个按层级单调递增的标题栈：遇到层级为 L 的标题时，弹出栈中所有层级 ≥ L 的标题，
     * 再压入当前标题。{@code position} 处栈中从根到叶的标题即构成路径。
     *
     * <p>示例：文本含「第一章」「第二节」「第三条」且 {@code position} 落在「第三条」之后，
     * 返回 {@code ["第一章", "第二节", "第三条"]}；若 {@code position} 落在「第二节」与
     * 「第三条」之间，返回 {@code ["第一章", "第二节"]}。
     *
     * @param text     源文本
     * @param position 字符偏移（0 基）；超出文本长度时按末尾处理，为负值时返回空列表
     * @return 从根到叶的标题路径；无标题或位置非法时返回空列表
     */
    public List<String> parseHeadingPath(String text, int position) {
        if (text == null || text.isEmpty() || position < 0) {
            return List.of();
        }
        int scanLimit = Math.min(position, text.length());

        // 收集扫描区间内的所有标题出现点（含恰好起始于 position 的标题，
        // 因为该标题正是 position 处内容所属的章节）
        List<HeadingOccurrence> occurrences = new ArrayList<>();

        Matcher md = MARKDOWN_HEADING_PATTERN.matcher(text);
        while (md.find()) {
            if (md.start() > scanLimit) {
                break;
            }
            int level = countLeadingHashes(md.group(0));
            occurrences.add(new HeadingOccurrence(md.start(), level, md.group(1).trim()));
        }

        Matcher legal = LEGAL_HEADING_PATTERN.matcher(text);
        while (legal.find()) {
            if (legal.start() > scanLimit) {
                break;
            }
            String match = legal.group();
            char suffix = match.charAt(match.length() - 1);
            int level = LEGAL_LEVELS.getOrDefault(suffix, 99);
            occurrences.add(new HeadingOccurrence(legal.start(), level, match));
        }

        // 按出现位置排序，确保标题栈反映文档真实结构
        occurrences.sort(Comparator.comparingInt(HeadingOccurrence::position));

        // 构建层级单调递增栈
        Deque<HeadingOccurrence> stack = new ArrayDeque<>();
        for (HeadingOccurrence occ : occurrences) {
            while (!stack.isEmpty() && stack.peek().level() >= occ.level()) {
                stack.pop();
            }
            stack.push(occ);
        }

        // 栈顶为最深标题；descendingIterator 从栈底（根）到栈顶（叶）
        List<String> path = new ArrayList<>(stack.size());
        stack.descendingIterator().forEachRemaining(o -> path.add(o.text()));
        return path;
    }

    /**
     * 检测单行文本是否为标题，返回密封类型 {@link HeadingMatch} 便于模式匹配解构。
     *
     * <p>Markdown 标题需整行匹配 {@code ^#{1,6}\s+(.+)$}；中文法规标题匹配行首
     * {@code 第…[章节条款款项编篇]}。两者均不命中时返回 {@code null}。
     *
     * @param line 待检测文本行（将先做 trim）
     * @return 标题匹配结果，或 {@code null} 表示非标题
     */
    public HeadingMatch detectHeading(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();

        Matcher md = MARKDOWN_HEADING_PATTERN.matcher(trimmed);
        if (md.matches()) {
            return new MarkdownHeading(md.group(1).trim(), countLeadingHashes(trimmed));
        }

        Matcher legal = LEGAL_HEADING_PATTERN.matcher(trimmed);
        if (legal.find()) {
            String match = legal.group();
            char suffix = match.charAt(match.length() - 1);
            return new LegalHeading(match, LEGAL_LEVELS.getOrDefault(suffix, 99));
        }
        return null;
    }

    // ====================================================================
    // 父分块上下文提取
    // ====================================================================

    /**
     * 提取包裹给定区间的父分块内容摘录。
     *
     * <p>父子文档策略下，父分块为子分块提供上下文。本方法以「标题边界对齐 + 尺寸兜底」
     * 确定父分块区间：
     * <ol>
     *   <li>向后从 {@code chunkStart} 在父分块尺寸窗口内查找最近的标题作为父区间起点
     *       （找不到则用窗口下界）；</li>
     *   <li>向前从 {@code chunkEnd} 在父分块尺寸窗口内查找最近的标题作为父区间终点
     *       （找不到则用窗口上界）；</li>
     *   <li>取该区间文本，超过 {@link #PARENT_EXCERPT_LIMIT} 时截断并补省略号。</li>
     * </ol>
     *
     * <p>这样父分块边界尽量与章节/标题对齐，避免将跨主题内容混入同一父分块，
     * 同时以尺寸窗口兜底保证父分块不会过大。
     *
     * @param sourceText 源文本
     * @param chunkStart 子区间起点（含）；负值或非法时返回空串
     * @param chunkEnd   子区间终点（不含）
     * @return 父分块内容摘录；源文本为空或区间非法时返回空串
     */
    public String extractParentContext(String sourceText, int chunkStart, int chunkEnd) {
        if (sourceText == null || sourceText.isBlank() || chunkStart < 0) {
            return "";
        }
        int len = sourceText.length();
        chunkStart = Math.min(chunkStart, len);
        chunkEnd = Math.min(Math.max(chunkEnd, chunkStart), len);

        RagProperties.Chunking cfg = properties.getChunking();
        int parentSize = Math.max(cfg.getChunkSize() * PARENT_SIZE_MULTIPLIER,
                cfg.getChunkSize() + cfg.getChunkOverlap());

        int parentStart = findHeadingBoundaryBefore(sourceText, chunkStart, parentSize);
        int parentEnd = findHeadingBoundaryAfter(sourceText, chunkEnd, parentSize, len);

        if (parentEnd <= parentStart) {
            parentEnd = Math.min(len, parentStart + parentSize);
        }

        String parentContent = sourceText.substring(parentStart, parentEnd);
        if (parentContent.length() > PARENT_EXCERPT_LIMIT) {
            parentContent = parentContent.substring(0, PARENT_EXCERPT_LIMIT) + "…";
        }
        return parentContent.trim();
    }

    // ====================================================================
    // 语义边界检测
    // ====================================================================

    /**
     * 检测两段相邻文本之间是否发生主题切换（语义边界）。
     *
     * <p>法规/技术文档在篇、章、节、条等结构边界处常发生主题漂移。本方法综合两类信号：
     * <ol>
     *   <li><b>结构信号</b>：{@code after} 首行为新标题（Markdown 或法规标题）→ 判定边界；</li>
     *   <li><b>语义信号</b>：取 {@code before} 尾部与 {@code after} 首部各
     *       {@link #BOUNDARY_WINDOW} 字符，计算词汇 Jaccard 重合度，低于
     *       {@link #BOUNDARY_OVERLAP_THRESHOLD} → 判定主题切换。</li>
     * </ol>
     *
     * <p>任一信号命中即返回 {@code true}。该判定可用于指导父分块在主题边界处对齐，
     * 避免跨主题内容被合并进同一父分块，从而保持父分块语境的内聚性。
     *
     * @param before 前一段文本（可为 null/空，视作边界）
     * @param after  后一段文本（可为 null/空，视作无边界）
     * @return true 表示检测到主题切换/语义边界
     */
    public boolean detectSemanticBoundary(String before, String after) {
        if (after == null || after.isBlank()) {
            return false;
        }
        if (before == null || before.isBlank()) {
            return true;
        }

        // 结构信号：后段以新标题起始
        String firstLine = firstLine(after);
        HeadingMatch heading = detectHeading(firstLine);
        if (heading != null) {
            // 利用密封类型 + 模式匹配解构（含 null 分支）展示 Java 21 特性
            int level = headingLevel(heading);
            log.trace("Semantic boundary: new heading '{}' at level {}", heading.text(), level);
            return true;
        }

        // 语义信号：相邻文本词汇重合度骤降
        String tail = tail(before, BOUNDARY_WINDOW);
        String head = head(after, BOUNDARY_WINDOW);
        double overlap = tokenJaccard(tail, head);
        if (overlap < BOUNDARY_OVERLAP_THRESHOLD) {
            log.trace("Semantic boundary: lexical overlap {} below threshold {}", overlap, BOUNDARY_OVERLAP_THRESHOLD);
            return true;
        }
        return false;
    }

    // ====================================================================
    // 私有辅助
    // ====================================================================

    /** 密封类型 + switch 模式匹配（含 null 分支）提取标题层级。 */
    private static int headingLevel(HeadingMatch heading) {
        return switch (heading) {
            case MarkdownHeading md -> md.level();
            case LegalHeading lg -> lg.level();
            case null -> -1;
        };
    }

    /** 统计行首 {@code #} 个数，作为 Markdown 标题层级。 */
    private static int countLeadingHashes(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return count;
    }

    /** 在源文本中顺序定位每个分块内容的字符区间，兼容重叠分块。 */
    private static List<ChunkSpan> locateChunks(List<DocumentChunker.Chunk> chunks, String sourceText) {
        List<ChunkSpan> spans = new ArrayList<>(chunks.size());
        int searchFrom = 0;
        for (DocumentChunker.Chunk chunk : chunks) {
            String content = chunk.content();
            int start = -1;
            if (content != null && !content.isEmpty()) {
                start = sourceText.indexOf(content, searchFrom);
                if (start < 0) {
                    // 回退：从全文开头重新搜索（应对重叠/乱序情况）
                    start = sourceText.indexOf(content);
                }
            }
            int end = (start >= 0) ? start + content.length() : -1;
            spans.add(new ChunkSpan(start, end));
            if (start >= 0) {
                // 向前推进至少 1 个字符，保证重叠分块也能顺序定位
                searchFrom = start + 1;
            }
        }
        return spans;
    }

    /** 生成父分块 ID：优先复用子分块元数据中的 docId 前缀，否则用 UUID。 */
    private static String generateParentId(DocumentChunker.Chunk firstChild, int parentIdx) {
        Map<String, String> meta = firstChild.metadata();
        String docId = (meta != null) ? meta.get("docId") : null;
        return (docId != null && !docId.isBlank())
                ? docId + ":parent-" + parentIdx
                : "parent-" + UUID.randomUUID();
    }

    /** 将当前父分组落盘到结果集合。 */
    private static void flushGroup(List<Integer> currentGroup, int groupStart, int groupEnd,
                                   String currentParentId,
                                   List<List<Integer>> groups, List<ChunkSpan> groupSpans,
                                   List<String> groupIds) {
        if (currentGroup == null || currentGroup.isEmpty() || currentParentId == null) {
            return;
        }
        groups.add(currentGroup);
        groupSpans.add(new ChunkSpan(groupStart, groupEnd));
        groupIds.add(currentParentId);
    }

    /**
     * 在 {@code pos} 之前、父分块尺寸窗口内，查找最近的标题起点作为父区间起点。
     * 找不到标题时回退到窗口下界（{@code pos - parentSize}，不小于 0）。
     */
    private static int findHeadingBoundaryBefore(String text, int pos, int parentSize) {
        int lowerBound = Math.max(0, pos - parentSize);
        int best = lowerBound;

        Matcher md = MARKDOWN_HEADING_PATTERN.matcher(text);
        while (md.find()) {
            if (md.start() > pos) {
                break;
            }
            if (md.start() >= lowerBound && md.start() > best) {
                best = md.start();
            }
        }

        Matcher legal = LEGAL_HEADING_PATTERN.matcher(text);
        while (legal.find()) {
            if (legal.start() > pos) {
                break;
            }
            if (legal.start() >= lowerBound && legal.start() > best) {
                best = legal.start();
            }
        }
        return best;
    }

    /**
     * 在 {@code pos} 之后、父分块尺寸窗口内，查找最近的标题起点作为父区间终点。
     * 找不到标题时回退到窗口上界（{@code pos + parentSize}，不超过文本长度）。
     */
    private static int findHeadingBoundaryAfter(String text, int pos, int parentSize, int len) {
        int upperBound = Math.min(len, pos + parentSize);
        int best = upperBound;

        Matcher md = MARKDOWN_HEADING_PATTERN.matcher(text);
        while (md.find()) {
            if (md.start() < pos) {
                continue;
            }
            if (md.start() <= upperBound && md.start() < best) {
                best = md.start();
            }
            break; // find() 已按位置顺序，首个 ≥ pos 的即最近
        }

        Matcher legal = LEGAL_HEADING_PATTERN.matcher(text);
        while (legal.find()) {
            if (legal.start() < pos) {
                continue;
            }
            if (legal.start() <= upperBound && legal.start() < best) {
                best = legal.start();
            }
            break;
        }
        return best;
    }

    /** 取字符串首行。 */
    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }

    /** 取字符串末尾 n 个字符。 */
    private static String tail(String s, int n) {
        return s.length() > n ? s.substring(s.length() - n) : s;
    }

    /** 取字符串开头 n 个字符。 */
    private static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 计算两段文本的词汇 Jaccard 重合度（CJK 二元组 + 拉丁/数字词，大小写不敏感）。 */
    private static double tokenJaccard(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) intersection.size() / union.size();
    }

    /** 分词：CJK 字符二元组 + 拉丁/数字连续串（小写）。 */
    private static Set<String> tokens(String text) {
        Set<String> set = new HashSet<>();
        StringBuilder latin = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (latin.length() > 0) {
                    set.add(latin.toString().toLowerCase());
                    latin.setLength(0);
                }
                if (prevCjk != 0) {
                    set.add("" + prevCjk + c);
                }
                prevCjk = c;
            } else {
                prevCjk = 0;
                if (Character.isLetterOrDigit(c)) {
                    latin.append(c);
                } else if (latin.length() > 0) {
                    set.add(latin.toString().toLowerCase());
                    latin.setLength(0);
                }
            }
        }
        if (latin.length() > 0) {
            set.add(latin.toString().toLowerCase());
        }
        return set;
    }

    /** 判断字符是否为 CJK 汉字（Unicode HAN 文字）。 */
    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    // ====================================================================
    // 内部记录类型
    // ====================================================================

    /** 标题出现点：位置、层级、文本。 */
    private record HeadingOccurrence(int position, int level, String text) {
    }

    /** 分块在源文本中的字符区间：[start, end)，-1 表示未定位。 */
    private record ChunkSpan(int start, int end) {
    }
}
