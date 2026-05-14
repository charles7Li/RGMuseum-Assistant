package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.RagRuntimeSettingsService;
import com.kama.jchatmind.service.RerankService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
public class RagServiceImpl implements RagService {

    private final WebClient webClient;
    private final String embeddingModel;
    private final MilvusVectorSearchService milvusVectorSearchService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RerankService rerankService;
    private final RagRuntimeSettingsService ragRuntimeSettingsService;

    private final boolean denseRecallEnabled;
    private final int denseRecallTopK;
    private final double denseRecallWeight;
    private final boolean lexicalRecallEnabled;
    private final int lexicalRecallTopK;
    private final double lexicalRecallWeight;
    private final int rrfK;
    private final double bm25K1;
    private final double bm25B;
    private final boolean dynamicRouteEnabled;
    private final int shortQueryCharThreshold;
    private final double shortQueryLexicalBoost;
    private final double idLikeLexicalBoost;
    private final double longQueryDenseBoost;
    private final boolean rerankEnabledByDefault;
    private final boolean rerankLowerScoreBetter;
    private final int rerankCandidateTopK;

    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("(?i)\\bartifact[_-]?\\d+\\b");
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("(?i)\\b[a-z]{1,6}[_-]?\\d{2,}\\b");

    public RagServiceImpl(
            WebClient.Builder builder,
            MilvusVectorSearchService milvusVectorSearchService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            RerankService rerankService,
            RagRuntimeSettingsService ragRuntimeSettingsService,
            @Value("${rag.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${rag.embedding.model:bge-m3}") String embeddingModel,
            @Value("${rag.recall.dense.enabled:true}") boolean denseRecallEnabled,
            @Value("${rag.recall.dense.topk:20}") int denseRecallTopK,
            @Value("${rag.recall.dense.weight:1.0}") double denseRecallWeight,
            @Value("${rag.recall.lexical.enabled:true}") boolean lexicalRecallEnabled,
            @Value("${rag.recall.lexical.topk:20}") int lexicalRecallTopK,
            @Value("${rag.recall.lexical.weight:0.8}") double lexicalRecallWeight,
            @Value("${rag.recall.rrf-k:60}") int rrfK,
            @Value("${rag.recall.lexical.bm25.k1:1.5}") double bm25K1,
            @Value("${rag.recall.lexical.bm25.b:0.75}") double bm25B,
            @Value("${rag.route.dynamic-enabled:true}") boolean dynamicRouteEnabled,
            @Value("${rag.route.short-query-char-threshold:12}") int shortQueryCharThreshold,
            @Value("${rag.route.short-query-lexical-boost:0.5}") double shortQueryLexicalBoost,
            @Value("${rag.route.id-like-lexical-boost:0.6}") double idLikeLexicalBoost,
            @Value("${rag.route.long-query-dense-boost:0.3}") double longQueryDenseBoost,
            @Value("${rag.rerank.enabled:true}") boolean rerankEnabledByDefault,
            @Value("${rag.rerank.lower-score-better:false}") boolean rerankLowerScoreBetter,
            @Value("${rag.rerank.candidate-topk:10}") int rerankCandidateTopK
    ) {
        this.webClient = builder.baseUrl(ollamaBaseUrl).build();
        this.embeddingModel = embeddingModel;
        this.milvusVectorSearchService = milvusVectorSearchService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.rerankService = rerankService;
        this.ragRuntimeSettingsService = ragRuntimeSettingsService;
        this.denseRecallEnabled = denseRecallEnabled;
        this.denseRecallTopK = Math.max(1, denseRecallTopK);
        this.denseRecallWeight = Math.max(0D, denseRecallWeight);
        this.lexicalRecallEnabled = lexicalRecallEnabled;
        this.lexicalRecallTopK = Math.max(1, lexicalRecallTopK);
        this.lexicalRecallWeight = Math.max(0D, lexicalRecallWeight);
        this.rrfK = Math.max(1, rrfK);
        this.bm25K1 = Math.max(0.1D, bm25K1);
        this.bm25B = Math.min(1.0D, Math.max(0.0D, bm25B));
        this.dynamicRouteEnabled = dynamicRouteEnabled;
        this.shortQueryCharThreshold = Math.max(1, shortQueryCharThreshold);
        this.shortQueryLexicalBoost = Math.max(0D, shortQueryLexicalBoost);
        this.idLikeLexicalBoost = Math.max(0D, idLikeLexicalBoost);
        this.longQueryDenseBoost = Math.max(0D, longQueryDenseBoost);
        this.rerankEnabledByDefault = rerankEnabledByDefault;
        this.rerankLowerScoreBetter = rerankLowerScoreBetter;
        this.rerankCandidateTopK = Math.max(1, rerankCandidateTopK);
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    @Data
    private static class ScoredChunk {
        private final ChunkBgeM3 chunk;
        private final double score;
    }

    @Data
    private static class Bm25Doc {
        private final ChunkBgeM3 chunk;
        private final Map<String, Integer> termFreq;
        private final int length;
    }

    @Data
    private static class RecallPlan {
        private final double denseWeight;
        private final int denseTopK;
        private final double lexicalWeight;
        private final int lexicalTopK;
        private final String routeType;
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return similaritySearch(kbId, title, null);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title, String artifactId) {
        List<ChunkBgeM3> chunks = similaritySearchChunks(kbId, title, 3, artifactId);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<ChunkBgeM3> similaritySearchChunks(String kbId, String query, int limit) {
        return similaritySearchChunks(kbId, query, limit, null);
    }

    @Override
    public List<ChunkBgeM3> similaritySearchChunks(String kbId, String query, int limit, String artifactId) {
        Assert.hasText(kbId, "kbId cannot be empty");
        Assert.hasText(query, "query cannot be empty");
        int safeLimit = Math.max(1, Math.min(50, limit));
        String normalizedArtifactId = normalizeArtifactId(artifactId);
        RecallPlan recallPlan = buildRecallPlan(query, normalizedArtifactId, safeLimit);

        List<ChunkBgeM3> denseCandidates = List.of();
        if (denseRecallEnabled && recallPlan.getDenseWeight() > 0D) {
            float[] embedding = doEmbed(query);
            int fetchSize = hasText(normalizedArtifactId)
                    ? Math.max(recallPlan.getDenseTopK(), safeLimit * 3)
                    : recallPlan.getDenseTopK();
            denseCandidates = milvusVectorSearchService.similaritySearch(kbId, embedding, fetchSize);
            denseCandidates = applyArtifactFilter(denseCandidates, normalizedArtifactId, fetchSize);
        }

        List<ChunkBgeM3> lexicalCandidates = List.of();
        if (lexicalRecallEnabled && recallPlan.getLexicalWeight() > 0D) {
            lexicalCandidates = bm25Search(kbId, query, normalizedArtifactId, recallPlan.getLexicalTopK());
        }

        if (denseCandidates.isEmpty() && lexicalCandidates.isEmpty()) {
            return List.of();
        }
        if (lexicalCandidates.isEmpty()) {
            return finalizeResults(query, denseCandidates, safeLimit);
        }
        if (denseCandidates.isEmpty()) {
            return finalizeResults(query, lexicalCandidates, safeLimit);
        }

        int fusedLimit = isRerankActive()
                ? Math.max(safeLimit, rerankCandidateTopK)
                : safeLimit;
        List<ChunkBgeM3> fusedCandidates = fuseByRrf(
                denseCandidates,
                lexicalCandidates,
                fusedLimit,
                recallPlan.getDenseWeight(),
                recallPlan.getLexicalWeight()
        );
        return finalizeResults(query, fusedCandidates, safeLimit);
    }

    private float[] doEmbed(String text) {
        Assert.hasText(text, "text cannot be empty");
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", embeddingModel,
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        Assert.notNull(resp.getEmbedding(), "Embedding value cannot be null");
        return resp.getEmbedding();
    }

    private List<ChunkBgeM3> bm25Search(String kbId, String query, String artifactId, int limit) {
        List<String> queryTerms = tokenizeForBm25(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        Set<String> uniqueQueryTerms = new HashSet<>(queryTerms);

        List<ChunkBgeM3> corpus = hasText(artifactId)
                ? chunkBgeM3Mapper.selectByKbIdAndArtifactId(kbId, artifactId)
                : chunkBgeM3Mapper.selectByKbId(kbId);
        if (corpus == null || corpus.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> docFreq = new HashMap<>();
        List<Bm25Doc> docs = new ArrayList<>(corpus.size());
        double totalLength = 0D;

        for (ChunkBgeM3 chunk : corpus) {
            if (chunk == null || !hasText(chunk.getContent())) {
                continue;
            }
            List<String> terms = tokenizeForBm25(chunk.getContent());
            if (terms.isEmpty()) {
                continue;
            }

            Map<String, Integer> termFreq = new HashMap<>();
            Set<String> matched = new HashSet<>();
            for (String term : terms) {
                termFreq.merge(term, 1, Integer::sum);
                if (uniqueQueryTerms.contains(term)) {
                    matched.add(term);
                }
            }
            for (String term : matched) {
                docFreq.merge(term, 1, Integer::sum);
            }

            docs.add(new Bm25Doc(chunk, termFreq, terms.size()));
            totalLength += terms.size();
        }

        if (docs.isEmpty()) {
            return List.of();
        }

        double avgDocLength = Math.max(1D, totalLength / docs.size());
        int totalDocs = docs.size();
        List<ScoredChunk> scored = new ArrayList<>(totalDocs);

        for (Bm25Doc doc : docs) {
            double score = 0D;
            for (String term : uniqueQueryTerms) {
                int tf = doc.getTermFreq().getOrDefault(term, 0);
                if (tf <= 0) {
                    continue;
                }
                int df = docFreq.getOrDefault(term, 0);
                if (df <= 0) {
                    continue;
                }
                double idf = Math.log(1D + (totalDocs - df + 0.5D) / (df + 0.5D));
                double norm = 1D - bm25B + bm25B * (doc.getLength() / avgDocLength);
                double denom = tf + bm25K1 * norm;
                score += idf * (tf * (bm25K1 + 1D)) / Math.max(1e-9, denom);
            }
            if (score > 0D) {
                scored.add(new ScoredChunk(doc.getChunk(), score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .map(ScoredChunk::getChunk)
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<ChunkBgeM3> fuseByRrf(
            List<ChunkBgeM3> dense,
            List<ChunkBgeM3> lexical,
            int limit,
            double denseWeight,
            double lexicalWeight
    ) {
        Map<String, ScoredChunk> fused = new LinkedHashMap<>();
        mergeByRrf(fused, dense, denseWeight);
        mergeByRrf(fused, lexical, lexicalWeight);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .map(ScoredChunk::getChunk)
                .limit(Math.max(1, limit))
                .toList();
    }

    private RecallPlan buildRecallPlan(String query, String normalizedArtifactId, int safeLimit) {
        double denseWeight = denseRecallWeight;
        int denseTopK = denseRecallTopK;
        double lexicalWeight = lexicalRecallWeight;
        int lexicalTopK = lexicalRecallTopK;

        String routeType = "base";
        if (!dynamicRouteEnabled) {
            return new RecallPlan(denseWeight, denseTopK, lexicalWeight, lexicalTopK, routeType);
        }

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int effectiveLength = normalizedQuery.replaceAll("\\s+", "").length();
        boolean idLike = hasText(normalizedArtifactId) || isIdLikeQuery(normalizedQuery);
        boolean shortQuery = effectiveLength > 0 && effectiveLength <= shortQueryCharThreshold;
        boolean longSemanticQuery = isSemanticLongQuery(normalizedQuery, effectiveLength);

        if (idLike) {
            lexicalWeight += idLikeLexicalBoost;
            lexicalTopK = Math.max(lexicalTopK, safeLimit * 3);
            denseTopK = Math.max(denseTopK, safeLimit * 2);
            routeType = "id_like";
        } else if (shortQuery) {
            lexicalWeight += shortQueryLexicalBoost;
            lexicalTopK = Math.max(lexicalTopK, safeLimit * 2);
            denseTopK = Math.max(denseTopK, safeLimit);
            routeType = "short_exact";
        } else if (longSemanticQuery) {
            denseWeight += longQueryDenseBoost;
            denseTopK = Math.max(denseTopK, safeLimit * 3);
            lexicalTopK = Math.max(lexicalTopK, safeLimit);
            routeType = "long_semantic";
        }

        return new RecallPlan(
                Math.max(0D, denseWeight),
                Math.max(1, denseTopK),
                Math.max(0D, lexicalWeight),
                Math.max(1, lexicalTopK),
                routeType
        );
    }

    private boolean isIdLikeQuery(String query) {
        if (!hasText(query)) {
            return false;
        }
        return ARTIFACT_ID_PATTERN.matcher(query).find()
                || NUMERIC_ID_PATTERN.matcher(query).find()
                || query.contains("编号")
                || query.contains("id");
    }

    private boolean isSemanticLongQuery(String query, int effectiveLength) {
        if (effectiveLength >= shortQueryCharThreshold * 2) {
            return true;
        }
        return containsAny(query, "为什么", "原理", "原因", "如何", "影响", "意义", "背景", "演变", "联系", "区别");
    }

    private boolean containsAny(String text, String... keywords) {
        if (!hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void mergeByRrf(Map<String, ScoredChunk> fused, List<ChunkBgeM3> route, double weight) {
        if (route == null || route.isEmpty() || weight <= 0D) {
            return;
        }
        for (int i = 0; i < route.size(); i++) {
            ChunkBgeM3 chunk = route.get(i);
            if (chunk == null) {
                continue;
            }
            String key = chunkKey(chunk);
            double delta = weight / (rrfK + i + 1.0);
            ScoredChunk old = fused.get(key);
            if (old == null) {
                fused.put(key, new ScoredChunk(chunk, delta));
            } else {
                fused.put(key, new ScoredChunk(old.getChunk(), old.getScore() + delta));
            }
        }
    }

    private List<ChunkBgeM3> applyArtifactFilter(List<ChunkBgeM3> chunks, String artifactId, int limit) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (!hasText(artifactId)) {
            return chunks.stream().limit(limit).toList();
        }
        String keyword = artifactId.toLowerCase(Locale.ROOT);
        List<ChunkBgeM3> filtered = new ArrayList<>();
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String content = chunk.getContent();
            if (content != null && content.toLowerCase(Locale.ROOT).contains(keyword)) {
                filtered.add(chunk);
            }
            if (filtered.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return filtered;
    }

    private List<ChunkBgeM3> finalizeResults(String query, List<ChunkBgeM3> candidates, int finalLimit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int safeFinalLimit = Math.max(1, finalLimit);
        if (!isRerankActive()) {
            return candidates.stream().limit(safeFinalLimit).toList();
        }

        int candidateLimit = Math.max(safeFinalLimit, rerankCandidateTopK);
        List<ChunkBgeM3> rerankCandidates = candidates.stream()
                .filter(chunk -> chunk != null)
                .limit(candidateLimit)
                .toList();
        if (rerankCandidates.isEmpty()) {
            return List.of();
        }

        List<String> documents = rerankCandidates.stream()
                .map(chunk -> chunk.getContent() == null ? "" : chunk.getContent())
                .toList();
        try {
            List<Double> scores = rerankService.rerank(query, documents);
            if (scores == null || scores.size() != rerankCandidates.size()) {
                log.warn("rerank response size mismatch, fallback to recall order: expected={}, actual={}",
                        rerankCandidates.size(), scores == null ? 0 : scores.size());
                return rerankCandidates.stream().limit(safeFinalLimit).toList();
            }
            List<ScoredChunk> reranked = new ArrayList<>(rerankCandidates.size());
            for (int i = 0; i < rerankCandidates.size(); i++) {
                reranked.add(new ScoredChunk(rerankCandidates.get(i), scores.get(i)));
            }
            Comparator<ScoredChunk> comparator = Comparator.comparingDouble(ScoredChunk::getScore);
            if (!rerankLowerScoreBetter) {
                comparator = comparator.reversed();
            }
            return reranked.stream()
                    .sorted(comparator)
                    .map(ScoredChunk::getChunk)
                    .limit(safeFinalLimit)
                    .toList();
        } catch (Exception e) {
            log.warn("rerank failed, fallback to recall order: {}", e.getMessage());
            return rerankCandidates.stream().limit(safeFinalLimit).toList();
        }
    }

    private boolean isRerankActive() {
        return rerankEnabledByDefault && ragRuntimeSettingsService.isRerankEnabled();
    }

    private List<String> tokenizeForBm25(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);

            if (isCjkCodePoint(cp)) {
                flushWordBuffer(tokens, buffer);
                tokens.add(new String(Character.toChars(cp)));
                continue;
            }

            if (Character.isLetterOrDigit(cp)) {
                buffer.appendCodePoint(cp);
            } else {
                flushWordBuffer(tokens, buffer);
            }
        }
        flushWordBuffer(tokens, buffer);
        return tokens;
    }

    private boolean isCjkCodePoint(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private void flushWordBuffer(List<String> tokens, StringBuilder wordBuffer) {
        if (wordBuffer.length() == 0) {
            return;
        }
        tokens.add(wordBuffer.toString());
        wordBuffer.setLength(0);
    }

    private String chunkKey(ChunkBgeM3 chunk) {
        if (chunk.getId() != null && !chunk.getId().isBlank()) {
            return chunk.getId();
        }
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        return (chunk.getDocId() == null ? "" : chunk.getDocId()) + "#" + content.hashCode();
    }

    private String normalizeArtifactId(String artifactId) {
        if (!hasText(artifactId)) {
            return null;
        }
        return artifactId.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
