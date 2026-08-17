package com.contentops.common.upload;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 用户人工上传：封面图片 / 创作素材文档。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "文件上传")
public class UploadController {

    private final FileStorageService fileStorageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文件（封面图片或创作素材文档）")
    public AgentResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", required = false) String purpose) {
        FileStorageService.StoredFile stored = fileStorageService.store(
                file, purpose == null ? "material" : purpose);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", stored.fileId());
        data.put("url", stored.url());
        data.put("name", stored.originalName());
        data.put("contentType", stored.contentType());
        return AgentResponse.success("upload", data);
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "读取上传文件（公开只读，供封面/预览使用）")
    public ResponseEntity<Resource> read(@PathVariable String fileId) {
        Optional<Path> path = fileStorageService.resolve(fileId);
        if (path.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(path.get());
        String ext = fileId.substring(fileId.lastIndexOf('.') + 1).toLowerCase();
        MediaType mediaType = switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "gif" -> MediaType.IMAGE_GIF;
            default -> MediaType.TEXT_PLAIN;
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header("Cache-Control", "public, max-age=3600")
                .body(resource);
    }
}
