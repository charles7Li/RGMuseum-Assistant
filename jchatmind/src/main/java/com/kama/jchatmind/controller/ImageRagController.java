package com.kama.jchatmind.controller;

import com.kama.jchatmind.mapper.ImageEmbeddingMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import com.kama.jchatmind.model.request.ImageRetrieveRequest;
import com.kama.jchatmind.model.response.ImageRetrieveResponse;
import com.kama.jchatmind.model.response.ImageUploadResponse;
import com.kama.jchatmind.model.vo.ImageRetrieveHitVO;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.ImageRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ImageRagController {

    private final ImageRagService imageRagService;
    private final ImageEmbeddingMapper imageEmbeddingMapper;
    private final DocumentStorageService documentStorageService;
    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    // 上传图像并完成向量化索引。
    @PostMapping({"/api/rag/images/upload", "/rag/images/upload"})
    public ApiResponse<ImageUploadResponse> uploadAndIndex(
            @RequestParam("kbId") String kbId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(imageRagService.uploadAndIndexImage(kbId, file));
    }

    // 根据文本查询检索相关图像。
    @PostMapping({"/api/rag/images/retrieve", "/rag/images/retrieve"})
    public ApiResponse<ImageRetrieveResponse> retrieve(@RequestBody ImageRetrieveRequest request) {
        Assert.notNull(request, "request cannot be null");
        Assert.hasText(request.getKbId(), "kbId cannot be empty");
        Assert.hasText(request.getQuery(), "query cannot be empty");

        int topK = request.getTopK() == null ? 3 : request.getTopK();
        List<ImageEmbedding> records = imageRagService.retrieveByText(request.getKbId(), request.getQuery(), topK);
        List<ImageRetrieveHitVO> hits = new ArrayList<>();
        for (ImageEmbedding record : records) {
            hits.add(ImageRetrieveHitVO.builder()
                    .imageId(record.getId())
                    .docId(record.getDocId())
                    .fileName(record.getFileName())
                    .filePath(record.getFilePath())
                    .imageUrl(trimTrailingSlash(publicBaseUrl) + "/api/rag/images/content/" + record.getId())
                    .build());
        }
        return ApiResponse.success(ImageRetrieveResponse.builder()
                .hits(hits.toArray(new ImageRetrieveHitVO[0]))
                .build());
    }

    // 按 imageId 返回图片二进制内容。
    @GetMapping({"/api/rag/images/content/{imageId}", "/rag/images/content/{imageId}"})
    public ResponseEntity<byte[]> getImageContent(@PathVariable String imageId) throws IOException {
        Assert.hasText(imageId, "imageId cannot be empty");
        ImageEmbedding image = imageEmbeddingMapper.selectById(imageId);
        if (image == null || !StringUtils.hasText(image.getFilePath())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path path = documentStorageService.getFilePath(image.getFilePath());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] bytes = Files.readAllBytes(path);
        String contentType = Files.probeContentType(path);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (StringUtils.hasText(contentType)) {
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=300")
                .body(bytes);
    }

    // 去除 URL 末尾斜杠并提供默认值。
    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8080";
        }
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
