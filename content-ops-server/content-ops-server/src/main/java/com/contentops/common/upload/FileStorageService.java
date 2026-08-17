package com.contentops.common.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 用户人工上传文件：封面图片 / 创作素材文档。
 * 文件落盘到本地目录，通过 {@code /api/v1/files/{fileId}} 提供只读访问。
 */
@Slf4j
@Component
public class FileStorageService {

    private static final Set<String> IMAGE_EXTS =
            Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final Set<String> DOC_EXTS =
            Set.of("md", "txt", "pdf", "docx", "doc");
    private static final long MAX_SIZE_BYTES = 20 * 1024 * 1024L;

    @Value("${contentops.upload.dir:content-outputs/uploads}")
    private String uploadDir;

    public record StoredFile(String fileId, String url, String originalName, String contentType) {
    }

    /**
     * 校验并存储上传文件。
     *
     * @param purpose image（封面/配图）或 material（创作素材文档）
     */
    public StoredFile store(MultipartFile file, String purpose) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("文件超过 20MB 上限");
        }
        String ext = extension(file.getOriginalFilename());
        boolean image = "image".equalsIgnoreCase(purpose) || IMAGE_EXTS.contains(ext);
        Set<String> allowed = image ? IMAGE_EXTS : DOC_EXTS;
        if (!allowed.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext
                    + "（允许: " + String.join("/", allowed) + "）");
        }

        String fileId = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path dir = Paths.get(uploadDir, datePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileId).normalize();
            if (!target.startsWith(dir)) {
                throw new IllegalArgumentException("非法文件路径");
            }
            file.transferTo(target);
            log.info("[Upload] 文件已保存: fileId={}, name={}, size={}, purpose={}",
                    fileId, file.getOriginalFilename(), file.getSize(), purpose);
            return new StoredFile(
                    fileId,
                    "/api/v1/files/" + fileId,
                    file.getOriginalFilename(),
                    file.getContentType() == null ? "" : file.getContentType());
        } catch (IOException e) {
            log.error("[Upload] 文件保存失败: {}", e.getMessage(), e);
            throw new IllegalArgumentException("文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 解析 fileId 对应的文件路径（供下载/读取）。
     */
    public Optional<Path> resolve(String fileId) {
        if (fileId == null || fileId.isBlank() || fileId.contains("..") || fileId.contains("/") || fileId.contains("\\")) {
            return Optional.empty();
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.getFileName() != null
                            && fileId.equals(p.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            log.warn("[Upload] 查找文件失败: fileId={}, err={}", fileId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 文本类素材直接读全文（md/txt）；其他格式返回空（仅保存引用）。
     */
    public String readTextContent(Path path, String fileId) {
        String ext = extension(fileId);
        if (!"md".equals(ext) && !"txt".equals(ext)) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Upload] 读取文本内容失败: fileId={}", fileId);
            return "";
        }
    }

    private String extension(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
