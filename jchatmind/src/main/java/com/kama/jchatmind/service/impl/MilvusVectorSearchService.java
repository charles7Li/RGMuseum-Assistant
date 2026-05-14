package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MilvusVectorSearchService {

    private static final int TYPE_VARCHAR = 21;
    private static final int TYPE_FLOAT_VECTOR = 101;

    private final WebClient webClient;
    private final String textCollectionName;
    private final String imageCollectionName;
    private final String annsField;
    private final String searchPath;
    private final String entitiesPath;
    private final String metricType;
    private final int nprobe;

    public MilvusVectorSearchService(
            WebClient.Builder builder,
            @Value("${rag.milvus.base-url:http://localhost:9091}") String milvusBaseUrl,
            @Value("${rag.milvus.token:}") String milvusToken,
            @Value("${rag.milvus.collection:chunk_bge_m3}") String textCollectionName,
            @Value("${rag.milvus.image-collection:image_embedding}") String imageCollectionName,
            @Value("${rag.milvus.anns-field:embedding}") String annsField,
            @Value("${rag.milvus.search-path:/api/v1/search}") String searchPath,
            @Value("${rag.milvus.entities-path:/api/v1/entities}") String entitiesPath,
            @Value("${rag.milvus.metric-type:L2}") String metricType,
            @Value("${rag.milvus.nprobe:16}") int nprobe
    ) {
        WebClient.Builder configuredBuilder = builder
                .baseUrl(milvusBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (StringUtils.hasText(milvusToken)) {
            configuredBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + milvusToken.trim());
        }
        this.webClient = configuredBuilder.build();
        this.textCollectionName = textCollectionName;
        this.imageCollectionName = imageCollectionName;
        this.annsField = annsField;
        this.searchPath = searchPath;
        this.entitiesPath = entitiesPath;
        this.metricType = metricType;
        this.nprobe = Math.max(1, nprobe);
    }

    public void insertBatch(List<ChunkBgeM3> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<ChunkBgeM3> safe = chunks.stream()
                .filter(chunk -> chunk != null && StringUtils.hasText(chunk.getId()) && chunk.getEmbedding() != null)
                .toList();
        if (safe.isEmpty()) {
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection_name", textCollectionName);
        body.put("num_rows", safe.size());
        body.put("fields_data", List.of(
                varcharField("id", safe.stream().map(ChunkBgeM3::getId).map(this::safeString).toList()),
                varcharField("kb_id", safe.stream().map(ChunkBgeM3::getKbId).map(this::safeString).toList()),
                varcharField("doc_id", safe.stream().map(ChunkBgeM3::getDocId).map(this::safeString).toList()),
                varcharField("content", safe.stream().map(ChunkBgeM3::getContent).map(this::safeString).toList()),
                varcharField("metadata", safe.stream().map(ChunkBgeM3::getMetadata).map(this::safeString).toList()),
                vectorField("embedding", safe.stream().map(ChunkBgeM3::getEmbedding).map(this::toFloatList).toList())
        ));

        JsonNode resp = webClient.post()
                .uri(entitiesPath)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        ensureStatusOk(resp, "milvus entities insert(text)");
        log.debug("Milvus insert text entities count={}, resp={}", safe.size(), resp);
    }

    public void insertImage(ImageEmbedding imageEmbedding) {
        if (imageEmbedding == null
                || !StringUtils.hasText(imageEmbedding.getId())
                || imageEmbedding.getEmbedding() == null
                || imageEmbedding.getEmbedding().length == 0) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection_name", imageCollectionName);
        body.put("num_rows", 1);
        body.put("fields_data", List.of(
                varcharField("id", List.of(safeString(imageEmbedding.getId()))),
                varcharField("kb_id", List.of(safeString(imageEmbedding.getKbId()))),
                varcharField("doc_id", List.of(safeString(imageEmbedding.getDocId()))),
                varcharField("file_name", List.of(safeString(imageEmbedding.getFileName()))),
                varcharField("file_path", List.of(safeString(imageEmbedding.getFilePath()))),
                varcharField("metadata", List.of(safeString(imageEmbedding.getMetadata()))),
                vectorField("embedding", List.of(toFloatList(imageEmbedding.getEmbedding())))
        ));

        JsonNode resp = webClient.post()
                .uri(entitiesPath)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        ensureStatusOk(resp, "milvus entities insert(image)");
        log.debug("Milvus insert image entity id={}, resp={}", imageEmbedding.getId(), resp);
    }

    public List<ChunkBgeM3> similaritySearch(String kbId, float[] queryEmbedding, int limit) {
        if (!StringUtils.hasText(kbId) || queryEmbedding == null || queryEmbedding.length == 0 || limit <= 0) {
            return List.of();
        }
        JsonNode resp = search(
                textCollectionName,
                kbId,
                queryEmbedding,
                limit,
                List.of("id", "kb_id", "doc_id", "content", "metadata")
        );
        return parseChunkResults(resp);
    }

    public List<ImageEmbedding> similaritySearchImages(String kbId, float[] queryEmbedding, int limit) {
        if (!StringUtils.hasText(kbId) || queryEmbedding == null || queryEmbedding.length == 0 || limit <= 0) {
            return List.of();
        }
        JsonNode resp = search(
                imageCollectionName,
                kbId,
                queryEmbedding,
                limit,
                List.of("id", "kb_id", "doc_id", "file_name", "file_path", "metadata")
        );
        return parseImageResults(resp);
    }

    private JsonNode search(String collectionName,
                            String kbId,
                            float[] queryEmbedding,
                            int limit,
                            List<String> outputFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection_name", collectionName);
        body.put("vectors", List.of(toFloatList(queryEmbedding)));
        body.put("dsl", "kb_id == \"" + kbId + "\"");
        body.put("dsl_type", 1);
        body.put("output_fields", outputFields);
        body.put("search_params", List.of(
                kv("anns_field", annsField),
                kv("topk", String.valueOf(limit)),
                kv("metric_type", metricType),
                kv("params", "{\"nprobe\":" + nprobe + "}")
        ));

        JsonNode resp = webClient.post()
                .uri(searchPath)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        ensureStatusOk(resp, "milvus search");
        return resp;
    }

    private List<ChunkBgeM3> parseChunkResults(JsonNode resp) {
        JsonNode results = resp.path("results");
        List<String> ids = readStringColumn(results, "id");
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> kbIds = readStringColumn(results, "kb_id");
        List<String> docIds = readStringColumn(results, "doc_id");
        List<String> contents = readStringColumn(results, "content");
        List<String> metadatas = readStringColumn(results, "metadata");

        List<ChunkBgeM3> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            out.add(ChunkBgeM3.builder()
                    .id(at(ids, i))
                    .kbId(at(kbIds, i))
                    .docId(at(docIds, i))
                    .content(at(contents, i))
                    .metadata(at(metadatas, i))
                    .build());
        }
        return out;
    }

    private List<ImageEmbedding> parseImageResults(JsonNode resp) {
        JsonNode results = resp.path("results");
        List<String> ids = readStringColumn(results, "id");
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> kbIds = readStringColumn(results, "kb_id");
        List<String> docIds = readStringColumn(results, "doc_id");
        List<String> fileNames = readStringColumn(results, "file_name");
        List<String> filePaths = readStringColumn(results, "file_path");
        List<String> metadatas = readStringColumn(results, "metadata");
        List<Double> scores = readDoubleArray(results.path("scores"));

        List<ImageEmbedding> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            out.add(ImageEmbedding.builder()
                    .id(at(ids, i))
                    .kbId(at(kbIds, i))
                    .docId(at(docIds, i))
                    .fileName(at(fileNames, i))
                    .filePath(at(filePaths, i))
                    .metadata(at(metadatas, i))
                    .distance(at(scores, i))
                    .build());
        }
        return out;
    }

    private List<String> readStringColumn(JsonNode results, String fieldName) {
        JsonNode fieldsData = results.path("fields_data");
        if (!fieldsData.isArray()) {
            return List.of();
        }
        for (JsonNode field : fieldsData) {
            if (!fieldName.equals(field.path("field_name").asText())) {
                continue;
            }
            JsonNode data = field.path("Field").path("Scalars").path("Data").path("StringData").path("data");
            if (!data.isArray()) {
                data = field.path("field").path("Scalars").path("Data").path("StringData").path("data");
            }
            if (!data.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                values.add(item.asText(""));
            }
            return values;
        }
        return List.of();
    }

    private List<Double> readDoubleArray(JsonNode arr) {
        if (!arr.isArray()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(n.asDouble());
        }
        return out;
    }

    private Map<String, Object> varcharField(String fieldName, List<String> values) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", TYPE_VARCHAR);
        field.put("field_name", fieldName);
        field.put("field", values);
        return field;
    }

    private Map<String, Object> vectorField(String fieldName, List<List<Float>> vectors) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", TYPE_FLOAT_VECTOR);
        field.put("field_name", fieldName);
        field.put("field", vectors);
        return field;
    }

    private Map<String, Object> kv(String key, String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", value);
        return out;
    }

    private void ensureStatusOk(JsonNode resp, String action) {
        if (resp == null || resp.isNull()) {
            throw new IllegalStateException(action + " failed: empty response");
        }
        JsonNode status = resp.path("status");
        int errorCode = status.path("error_code").asInt(0);
        if (errorCode != 0) {
            String reason = status.path("reason").asText("unknown");
            throw new IllegalStateException(action + " failed: " + reason + " (error_code=" + errorCode + ")");
        }
    }

    private List<Float> toFloatList(float[] values) {
        List<Float> out = new ArrayList<>(values.length);
        for (float value : values) {
            out.add(value);
        }
        return out;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private <T> T at(List<T> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return null;
        }
        return values.get(index);
    }
}
