package com.contentops.common.knowledge;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File I/O tools for LangChain4j AI Services.
 *
 * <p>Provides {@code @Tool}-annotated methods that allow AI agents to read and write
 * local files. This implements the TRAE Work capability of "直接读写本地文件" —
 * each Agent's output can be persisted as Markdown/JSON files for later retrieval,
 * sharing, or archival.
 *
 * <p>Security: all file operations are sandboxed to {@code baseDir} (configurable).
 * Only files with allowed extensions (md, json, txt, csv) can be written.
 * Path traversal (../) is blocked.
 */
@Slf4j
@Component
public class FileTools {

    private final FileToolsProperties properties;
    private final Path baseDirPath;
    private final Set<String> allowedExtensions;

    public FileTools(FileToolsProperties properties) {
        this.properties = properties;
        this.baseDirPath = Paths.get(properties.getBaseDir()).toAbsolutePath().normalize();
        this.allowedExtensions = Set.of(properties.getAllowedExtensions().toLowerCase().split(","));
        log.info("FileTools initialized: baseDir={}, allowedExtensions={}",
                baseDirPath, allowedExtensions);
    }

    /**
     * Read a local file's content.
     *
     * @param relativePath file path relative to the base directory
     * @return the file content, or an error message
     */
    @Tool("读取本地文件内容。参数: relativePath - 相对于输出目录的文件路径")
    public String readLocalFile(String relativePath) {
        log.info("[Tool] readLocalFile invoked: path={}", relativePath);

        if (relativePath == null || relativePath.isBlank()) {
            return "[错误] 文件路径不能为空";
        }

        try {
            Path resolvedPath = resolveAndValidate(relativePath);
            if (resolvedPath == null) {
                return "[错误] 文件路径不合法或不在允许范围内: " + relativePath;
            }

            if (!Files.exists(resolvedPath)) {
                return "[错误] 文件不存在: " + relativePath;
            }

            String content = Files.readString(resolvedPath);
            log.info("[Tool] readLocalFile success: path={}, length={} chars", relativePath, content.length());
            return content;
        } catch (IOException e) {
            log.error("[Tool] readLocalFile failed: path={}", relativePath, e);
            return "[读取文件失败] " + e.getMessage();
        }
    }

    /**
     * Write content to a local file. Creates parent directories if needed.
     *
     * @param relativePath file path relative to the base directory
     * @param content      the content to write
     * @return success or error message
     */
    @Tool("将内容写入本地文件。参数: relativePath - 相对于输出目录的文件路径, content - 要写入的内容")
    public String writeLocalFile(String relativePath, String content) {
        log.info("[Tool] writeLocalFile invoked: path={}, contentLength={}",
                relativePath, content != null ? content.length() : 0);

        if (relativePath == null || relativePath.isBlank()) {
            return "[错误] 文件路径不能为空";
        }
        if (content == null) {
            content = "";
        }

        try {
            Path resolvedPath = resolveAndValidate(relativePath);
            if (resolvedPath == null) {
                return "[错误] 文件路径不合法或扩展名不被允许: " + relativePath
                        + " (允许的扩展名: " + properties.getAllowedExtensions() + ")";
            }

            // Create parent directories
            Files.createDirectories(resolvedPath.getParent());

            // Write content
            Files.writeString(resolvedPath, content);

            String absolutePath = resolvedPath.toString();
            log.info("[Tool] writeLocalFile success: path={}, bytes={}", absolutePath, content.length());
            return "[文件写入成功] 路径: " + absolutePath + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            log.error("[Tool] writeLocalFile failed: path={}", relativePath, e);
            return "[写入文件失败] " + e.getMessage();
        }
    }

    /**
     * List files in a directory relative to the base directory.
     *
     * @param relativeDir directory path relative to base (empty = base dir itself)
     * @return formatted listing of files and directories
     */
    @Tool("列出指定目录下的文件列表。参数: relativeDir - 相对于输出目录的目录路径（空字符串表示根目录）")
    public String listFiles(String relativeDir) {
        log.info("[Tool] listFiles invoked: dir={}", relativeDir);

        String dir = relativeDir != null ? relativeDir : "";
        try {
            Path resolvedDir = dir.isBlank() ? baseDirPath : resolveAndValidate(dir + "/dummy.md");
            if (resolvedDir == null) {
                return "[错误] 目录路径不合法: " + dir;
            }
            Path actualDir = dir.isBlank() ? baseDirPath : baseDirPath.resolve(dir).normalize();

            if (!Files.exists(actualDir) || !Files.isDirectory(actualDir)) {
                return "[错误] 目录不存在: " + dir;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[文件列表] 目录: ").append(dir.isBlank() ? "/" : dir).append("\n");

            try (Stream<Path> stream = Files.list(actualDir)) {
                stream.sorted()
                        .forEach(path -> {
                            String name = path.getFileName().toString();
                            if (Files.isDirectory(path)) {
                                sb.append("  📁 ").append(name).append("/\n");
                            } else {
                                long size = 0;
                                try { size = Files.size(path); } catch (IOException ignored) {}
                                sb.append("  📄 ").append(name)
                                        .append(" (").append(formatSize(size)).append(")\n");
                            }
                        });
            }

            return sb.toString();
        } catch (IOException e) {
            log.error("[Tool] listFiles failed: dir={}", dir, e);
            return "[列出文件失败] " + e.getMessage();
        }
    }

    /**
     * Generate a timestamped filename for an agent's output.
     *
     * @param agentCode  the agent code (e.g., "topic-planning")
     * @param extension  file extension without dot (e.g., "md", "json")
     * @return a path like "topic-planning/2026-07-24_153000.md"
     */
    @Tool("为Agent输出生成带时间戳的文件名。参数: agentCode - Agent代号, extension - 文件扩展名（如md/json）")
    public String generateOutputPath(String agentCode, String extension) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        String safeExt = extension != null ? extension.replace(".", "") : "md";
        String safeAgent = agentCode != null ? agentCode.replaceAll("[^a-zA-Z0-9-]", "_") : "output";
        return safeAgent + "/" + timestamp + "." + safeExt;
    }

    // ──────────────────── Security helpers ────────────────────

    /**
     * Resolve a relative path against the base directory and validate it.
     * Returns null if the path is invalid (traversal attempt or disallowed extension).
     */
    private Path resolveAndValidate(String relativePath) {
        // Normalize and check for path traversal
        Path resolved = baseDirPath.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDirPath)) {
            log.warn("[FileTools] Path traversal blocked: {} -> {}", relativePath, resolved);
            return null;
        }

        // Check file extension
        String fileName = resolved.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            String ext = fileName.substring(lastDot + 1).toLowerCase();
            if (!allowedExtensions.contains(ext)) {
                log.warn("[FileTools] Disallowed file extension: {} (allowed: {})", ext, allowedExtensions);
                return null;
            }
        }

        return resolved;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
