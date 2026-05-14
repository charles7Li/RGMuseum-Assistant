package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.RagMixedRetrieveRequest;
import com.kama.jchatmind.model.request.RagRetrieveRequest;
import com.kama.jchatmind.model.response.RagMixedRetrieveResponse;
import com.kama.jchatmind.model.response.RagRetrieveResponse;
import com.kama.jchatmind.model.vo.ImageRetrieveHitVO;
import com.kama.jchatmind.model.vo.RagRetrieveHitVO;
import com.kama.jchatmind.service.ImageRagService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.MilvusVectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequiredArgsConstructor
@Slf4j
public class RagController {

    private static final int MIGRATE_BATCH_SIZE = 100;

    private final RagService ragService;
    private final ImageRagService imageRagService;
    private final DocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final MilvusVectorSearchService milvusVectorSearchService;
    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    // 将 PostgreSQL 中全量 chunk 向量数据迁移到 Milvus。
    @PostMapping("/api/rag/migrate/milvus")
    public ApiResponse<Map<String, Object>> migrateToMilvus() {
        List<ChunkBgeM3> all = chunkBgeM3Mapper.selectList(null);
        int total = all.size();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (int i = 0; i < total; i += MIGRATE_BATCH_SIZE) {
            List<ChunkBgeM3> batch = all.subList(i, Math.min(i + MIGRATE_BATCH_SIZE, total));
            try {
                milvusVectorSearchService.insertBatch(batch);
                success.addAndGet(batch.size());
                log.info("Milvus migration progress: {}/{}", success.get(), total);
            } catch (Exception e) {
                failed.addAndGet(batch.size());
                log.error("Milvus migration batch failed, offset={}: {}", i, e.getMessage());
            }
        }

        return ApiResponse.success(Map.of(
                "total", total,
                "success", success.get(),
                "failed", failed.get()
        ));
    }

    // 执行文本检索并返回命中文本片段。
    @PostMapping({"/api/rag/retrieve", "/rag/retrieve"})
    public ApiResponse<RagRetrieveResponse> retrieve(@RequestBody RagRetrieveRequest request) {
        Assert.notNull(request, "request cannot be null");
        Assert.hasText(request.getKbId(), "kbId cannot be empty");
        Assert.hasText(request.getQuery(), "query cannot be empty");

        int topK = request.getTopK() == null ? 3 : request.getTopK();
        List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(request.getKbId(), request.getQuery(), topK);

        List<RagRetrieveHitVO> hits = new ArrayList<>();
        for (ChunkBgeM3 chunk : chunks) {
            String source = null;
            if (chunk.getDocId() != null) {
                Document doc = documentMapper.selectById(chunk.getDocId());
                if (doc != null) {
                    source = doc.getFilename();
                }
            }
            hits.add(RagRetrieveHitVO.builder()
                    .chunkId(chunk.getId())
                    .docId(chunk.getDocId())
                    .source(source)
                    .content(chunk.getContent())
                    .build());
        }

        return ApiResponse.success(RagRetrieveResponse.builder()
                .hits(hits.toArray(new RagRetrieveHitVO[0]))
                .build());
    }

    // 并行返回文本与图像两类检索结果。
    @PostMapping({"/api/rag/retrieve-mixed", "/rag/retrieve-mixed"})
    public ApiResponse<RagMixedRetrieveResponse> retrieveMixed(@RequestBody RagMixedRetrieveRequest request) {
        Assert.notNull(request, "request cannot be null");
        Assert.hasText(request.getKbId(), "kbId cannot be empty");
        Assert.hasText(request.getQuery(), "query cannot be empty");

        int textTopK = request.getTextTopK() == null ? 3 : request.getTextTopK();
        int imageTopK = request.getImageTopK() == null ? 3 : request.getImageTopK();

        List<RagRetrieveHitVO> textHits = buildTextHits(request.getKbId(), request.getQuery(), textTopK);
        List<ImageRetrieveHitVO> imageHits = buildImageHits(request.getKbId(), request.getQuery(), imageTopK);

        return ApiResponse.success(RagMixedRetrieveResponse.builder()
                .textHits(textHits.toArray(new RagRetrieveHitVO[0]))
                .imageHits(imageHits.toArray(new ImageRetrieveHitVO[0]))
                .build());
    }

    // 以 SSE 方式流式返回混合检索结果。
    @PostMapping(value = {"/api/rag/retrieve-mixed/stream", "/rag/retrieve-mixed/stream"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter retrieveMixedStream(@RequestBody RagMixedRetrieveRequest request) {
        Assert.notNull(request, "request cannot be null");
        Assert.hasText(request.getKbId(), "kbId cannot be empty");
        Assert.hasText(request.getQuery(), "query cannot be empty");

        int textTopK = request.getTextTopK() == null ? 3 : request.getTextTopK();
        int imageTopK = request.getImageTopK() == null ? 3 : request.getImageTopK();

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(SseEmitter.event().name("init").data("{\"status\":\"connected\"}"));

                List<RagRetrieveHitVO> textHits = buildTextHits(request.getKbId(), request.getQuery(), textTopK);
                emitter.send(SseEmitter.event()
                        .name("text_hits")
                        .data(objectMapper.writeValueAsString(textHits)));

                List<ImageRetrieveHitVO> imageHits = buildImageHits(request.getKbId(), request.getQuery(), imageTopK);
                emitter.send(SseEmitter.event()
                        .name("image_hits")
                        .data(objectMapper.writeValueAsString(imageHits)));

                emitter.send(SseEmitter.event().name("done").data("{\"status\":\"done\"}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("{\"message\":\"" + e.getMessage() + "\"}"));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // 构建文本检索命中视图对象。
    private List<RagRetrieveHitVO> buildTextHits(String kbId, String query, int topK) {
        List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbId, query, topK);
        List<RagRetrieveHitVO> textHits = new ArrayList<>();
        for (ChunkBgeM3 chunk : chunks) {
            String source = null;
            if (chunk.getDocId() != null) {
                Document doc = documentMapper.selectById(chunk.getDocId());
                if (doc != null) {
                    source = doc.getFilename();
                }
            }
            textHits.add(RagRetrieveHitVO.builder()
                    .chunkId(chunk.getId())
                    .docId(chunk.getDocId())
                    .source(source)
                    .content(chunk.getContent())
                    .build());
        }
        return textHits;
    }

    // 构建图像检索命中视图对象。
    private List<ImageRetrieveHitVO> buildImageHits(String kbId, String query, int topK) {
        List<ImageEmbedding> images = imageRagService.retrieveByText(kbId, query, topK);
        List<ImageRetrieveHitVO> imageHits = new ArrayList<>();
        for (ImageEmbedding image : images) {
            imageHits.add(ImageRetrieveHitVO.builder()
                    .imageId(image.getId())
                    .docId(image.getDocId())
                    .fileName(image.getFileName())
                    .filePath(image.getFilePath())
                    .imageUrl(trimTrailingSlash(publicBaseUrl) + "/api/rag/images/content/" + image.getId())
                    .build());
        }
        return imageHits;
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
