package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.ImageEmbeddingMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import com.kama.jchatmind.model.response.ImageUploadResponse;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.ImageRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ImageRagServiceImpl implements ImageRagService {

    private final HttpClient httpClient;
    private final String imageEmbeddingBaseUrl;
    private final String imageEmbeddingModel;
    private final DocumentStorageService documentStorageService;
    private final DocumentMapper documentMapper;
    private final ImageEmbeddingMapper imageEmbeddingMapper;
    private final MilvusVectorSearchService milvusVectorSearchService;
    private final ObjectMapper objectMapper;

    public ImageRagServiceImpl(
            DocumentStorageService documentStorageService,
            DocumentMapper documentMapper,
            ImageEmbeddingMapper imageEmbeddingMapper,
            MilvusVectorSearchService milvusVectorSearchService,
            ObjectMapper objectMapper,
            @Value("${rag.image-embedding.base-url:http://localhost:18000}") String imageEmbeddingBaseUrl,
            @Value("${rag.image-embedding.model:OFA-Sys/chinese-clip-vit-base-patch16}") String imageEmbeddingModel
    ) {
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.imageEmbeddingBaseUrl = trimTrailingSlash(imageEmbeddingBaseUrl);
        this.documentStorageService = documentStorageService;
        this.documentMapper = documentMapper;
        this.imageEmbeddingMapper = imageEmbeddingMapper;
        this.milvusVectorSearchService = milvusVectorSearchService;
        this.objectMapper = objectMapper;
        this.imageEmbeddingModel = imageEmbeddingModel;
    }

    @Override
    public ImageUploadResponse uploadAndIndexImage(String kbId, MultipartFile file) {
        Assert.hasText(kbId, "kbId cannot be empty");
        Assert.notNull(file, "file cannot be null");
        if (file.isEmpty()) {
            throw new BizException("uploaded file cannot be empty");
        }
        if (!isImageFile(file.getOriginalFilename(), file.getContentType())) {
            throw new BizException("only image file is supported");
        }
        try {
            return uploadAndIndexImageInternal(
                    kbId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    null
            );
        } catch (IOException e) {
            throw new BizException("read image file failed: " + e.getMessage());
        }
    }

    @Override
    public ImageUploadResponse uploadAndIndexImageBytes(String kbId,
                                                        String originalFilename,
                                                        String contentType,
                                                        byte[] bytes,
                                                        String sourceDocumentId) {
        Assert.hasText(kbId, "kbId cannot be empty");
        if (bytes == null || bytes.length == 0) {
            throw new BizException("image bytes cannot be empty");
        }
        String safeName = StringUtils.hasText(originalFilename) ? originalFilename : "markdown-image.png";
        if (!isImageFile(safeName, contentType)) {
            throw new BizException("only image file is supported");
        }
        return uploadAndIndexImageInternal(kbId, safeName, contentType, bytes, sourceDocumentId);
    }

    @Override
    public List<ImageEmbedding> retrieveByText(String kbId, String query, int topK) {
        Assert.hasText(kbId, "kbId cannot be empty");
        Assert.hasText(query, "query cannot be empty");
        int safeTopK = Math.max(1, Math.min(20, topK));
        float[] textEmbedding = embedText(query);
        return milvusVectorSearchService.similaritySearchImages(kbId, textEmbedding, safeTopK);
    }

    private ImageUploadResponse uploadAndIndexImageInternal(String kbId,
                                                            String originalFilename,
                                                            String contentType,
                                                            byte[] imageBytes,
                                                            String sourceDocumentId) {
        String fileType = resolveFileType(originalFilename, contentType);
        LocalDateTime now = LocalDateTime.now();

        Document doc = Document.builder()
                .kbId(kbId)
                .filename(originalFilename)
                .filetype(fileType)
                .size((long) imageBytes.length)
                .createdAt(now)
                .updatedAt(now)
                .build();
        int insertDoc = documentMapper.insert(doc);
        if (insertDoc <= 0 || !StringUtils.hasText(doc.getId())) {
            throw new BizException("create image document record failed");
        }

        try {
            String filePath = documentStorageService.saveBytes(kbId, doc.getId(), originalFilename, imageBytes);
            updateDocumentMetadata(doc, filePath, now, sourceDocumentId);

            float[] embedding = embedImage(imageBytes);
            String imageMetadataJson = buildImageEmbeddingMetadata(sourceDocumentId);
            ImageEmbedding imageEmbedding = ImageEmbedding.builder()
                    .kbId(kbId)
                    .docId(doc.getId())
                    .fileName(originalFilename)
                    .filePath(filePath)
                    .metadata(imageMetadataJson)
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            int insertEmbedding = imageEmbeddingMapper.insert(imageEmbedding);
            if (insertEmbedding <= 0 || !StringUtils.hasText(imageEmbedding.getId())) {
                throw new BizException("create image embedding failed");
            }
            milvusVectorSearchService.insertImage(imageEmbedding);
            return ImageUploadResponse.builder()
                    .imageId(imageEmbedding.getId())
                    .documentId(doc.getId())
                    .build();
        } catch (IOException e) {
            throw new BizException("save image failed: " + e.getMessage());
        }
    }

    private void updateDocumentMetadata(Document doc, String filePath, LocalDateTime now, String sourceDocumentId) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("filePath", filePath);
            metadata.put("modality", "image");
            if (StringUtils.hasText(sourceDocumentId)) {
                metadata.put("sourceDocumentId", sourceDocumentId);
            }
            Document update = Document.builder()
                    .id(doc.getId())
                    .kbId(doc.getKbId())
                    .filename(doc.getFilename())
                    .filetype(doc.getFiletype())
                    .size(doc.getSize())
                    .metadata(objectMapper.writeValueAsString(metadata))
                    .createdAt(doc.getCreatedAt())
                    .updatedAt(now)
                    .build();
            documentMapper.updateById(update);
        } catch (Exception e) {
            log.warn("update image document metadata failed, docId={}, err={}", doc.getId(), e.getMessage());
        }
    }

    private boolean isImageFile(String filename, String contentType) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }
        String ext = getFileType(filename);
        return ext.equals("png")
                || ext.equals("jpg")
                || ext.equals("jpeg")
                || ext.equals("webp")
                || ext.equals("bmp")
                || ext.equals("gif");
    }

    private String resolveFileType(String filename, String contentType) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType.substring("image/".length()).toLowerCase();
        }
        return getFileType(filename);
    }

    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private float[] embedText(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", imageEmbeddingModel);
        body.put("text", text);
        JsonNode resp = requestEmbed("/embed-text", body);
        return extractEmbedding(resp);
    }

    private float[] embedImage(byte[] imageBytes) {
        String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> body = new HashMap<>();
        body.put("model", imageEmbeddingModel);
        body.put("image", imageBase64);
        JsonNode resp = requestEmbed("/embed-image", body);
        return extractEmbedding(resp);
    }

    private JsonNode requestEmbed(String uri, Map<String, Object> body) {
        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageEmbeddingBaseUrl + uri))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("image embedding request failed, status=" + response.statusCode() + ", uri=" + uri);
            }
            String responseBody = response.body();
            if (!StringUtils.hasText(responseBody)) {
                throw new BizException("image embedding response body is empty");
            }
            return objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new BizException("image embedding request failed: " + e.getMessage());
        }
    }

    private float[] extractEmbedding(JsonNode response) {
        JsonNode embeddingNode = null;
        if (response.has("embedding") && response.get("embedding").isArray()) {
            embeddingNode = response.get("embedding");
        } else if (response.has("embeddings")
                && response.get("embeddings").isArray()
                && response.get("embeddings").size() > 0
                && response.get("embeddings").get(0).isArray()) {
            embeddingNode = response.get("embeddings").get(0);
        }
        if (embeddingNode == null) {
            throw new BizException("invalid image embedding response format");
        }

        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble(0D);
        }
        return embedding;
    }

    private String buildImageEmbeddingMetadata(String sourceDocumentId) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (StringUtils.hasText(sourceDocumentId)) {
                metadata.put("sourceDocumentId", sourceDocumentId);
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("build image embedding metadata failed: {}", e.getMessage());
            return "{}";
        }
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:18000";
        }
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
