package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import com.kama.jchatmind.service.ImageRagService;
import com.kama.jchatmind.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

@Component
public class KnowledgeTools implements Tool {

    private static final Pattern NON_ENTITY_PATTERN = Pattern.compile(
            "(你知道|你了解|请问|告诉我|介绍一下|介绍下|是什么|什么是|吗|呢|呀|吧|有吗|有没有|相关图片|图片呢|图呢|请)");

    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("(artifact_\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_DOCUMENT_ID_PATTERN =
            Pattern.compile("\"sourceDocumentId\"\\s*:\\s*\"([^\"]+)\"");
    private static final String RISK_BLOCK_MARKER = "riskStatus=blocked";

    private final RagService ragService;
    private final ImageRagService imageRagService;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;
    private final double maxImageDistance;

    // 初始化知识检索工具依赖与运行参数。
    public KnowledgeTools(RagService ragService,
                          ImageRagService imageRagService,
                          ObjectMapper objectMapper,
                          @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl,
                          @Value("${rag.image-retrieve.max-distance:0.80}") double maxImageDistance) {
        this.ragService = ragService;
        this.imageRagService = imageRagService;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
        this.maxImageDistance = maxImageDistance;
    }

    private List<ChunkBgeM3> retrieveTextChunks(String kbsId, String query, String artifactId, TaskType taskType) {
        int limit = switch (taskType) {
            case FACT_QA -> 4;
            case OBJECT_IDENTIFICATION -> 3;
            case SIMILAR_RETRIEVAL -> 4;
            case DETAIL_ANALYSIS -> 6;
            case COMPARISON_QA -> 6;
        };
        int candidateLimit = Math.max(limit + 3, 6);
        List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbsId, query, candidateLimit, artifactId);
        List<ChunkBgeM3> hits = new ArrayList<>();
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            hits.add(chunk);
        }
        return hits;
    }

    private List<ChunkBgeM3> retrieveFeatureRichTextChunks(String kbsId, String query, String artifactId, TaskType taskType) {
        int limit = switch (taskType) {
            case FACT_QA -> 5;
            case OBJECT_IDENTIFICATION -> 4;
            case SIMILAR_RETRIEVAL -> 4;
            case DETAIL_ANALYSIS -> 8;
            case COMPARISON_QA -> 8;
        };
        int candidateLimit = Math.max(limit + 4, 8);
        List<String> queryVariants = buildFeatureQueryVariants(query, artifactId);
        List<ChunkBgeM3> hits = new ArrayList<>();
        for (String variant : queryVariants) {
            List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbsId, variant, candidateLimit, artifactId);
            for (ChunkBgeM3 chunk : chunks) {
                if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                    continue;
                }
                hits.add(chunk);
            }
        }
        return prioritizeChunkEvidence(dedupeChunks(hits), query, taskType);
    }

    private List<ChunkBgeM3> dedupeChunks(List<ChunkBgeM3> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<ChunkBgeM3> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            String key = chunk.getContent().trim();
            if (seen.add(key)) {
                deduped.add(chunk);
            }
        }
        return deduped;
    }

    private List<ChunkBgeM3> prioritizeChunkEvidence(List<ChunkBgeM3> hits, String query, TaskType taskType) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ChunkBgeM3> ranked = new ArrayList<>(hits);
        ranked.sort((left, right) -> {
            int scoreRight = evidenceScore(right == null ? null : right.getContent(), query, taskType);
            int scoreLeft = evidenceScore(left == null ? null : left.getContent(), query, taskType);
            if (scoreRight != scoreLeft) {
                return Integer.compare(scoreRight, scoreLeft);
            }
            int lengthRight = right == null || right.getContent() == null ? 0 : right.getContent().length();
            int lengthLeft = left == null || left.getContent() == null ? 0 : left.getContent().length();
            return Integer.compare(lengthRight, lengthLeft);
        });
        return ranked;
    }

    private List<String> toTextHits(List<ChunkBgeM3> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> hits = new ArrayList<>(chunks.size());
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            hits.add(chunk.getContent());
        }
        return hits;
    }

    private Set<String> collectDocIds(List<ChunkBgeM3> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Set.of();
        }
        Set<String> docIds = new HashSet<>();
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk != null && hasText(chunk.getDocId())) {
                docIds.add(chunk.getDocId().trim());
            }
        }
        return docIds;
    }

    private List<ImageEmbedding> filterBySourceDocumentIds(List<ImageEmbedding> imageHits, Set<String> sourceDocIds) {
        if (imageHits == null || imageHits.isEmpty() || sourceDocIds == null || sourceDocIds.isEmpty()) {
            return List.of();
        }
        List<ImageEmbedding> filtered = new ArrayList<>();
        for (ImageEmbedding imageHit : imageHits) {
            if (imageHit == null) {
                continue;
            }
            String sourceDocumentId = extractSourceDocumentId(imageHit);
            if (hasText(sourceDocumentId) && sourceDocIds.contains(sourceDocumentId.trim())) {
                filtered.add(imageHit);
            }
        }
        return filtered;
    }

    private String extractSourceDocumentId(ImageEmbedding imageHit) {
        if (imageHit == null || !hasText(imageHit.getMetadata())) {
            return null;
        }
        String metadata = imageHit.getMetadata();
        try {
            JsonNode metadataNode = objectMapper.readTree(metadata);
            JsonNode sourceDocumentNode = metadataNode.get("sourceDocumentId");
            if (sourceDocumentNode != null && !sourceDocumentNode.isNull() && hasText(sourceDocumentNode.asText())) {
                return sourceDocumentNode.asText().trim();
            }
        } catch (Exception ignored) {
        }
        var matcher = SOURCE_DOCUMENT_ID_PATTERN.matcher(metadata);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private RetrieveMode resolveRetrieveMode(String retrieveMode, String query, TaskType taskType, String artifactId) {
        if (hasText(retrieveMode)) {
            String mode = retrieveMode.trim().toLowerCase(Locale.ROOT);
            if ("strict".equals(mode)) {
                return RetrieveMode.STRICT;
            }
            if ("explore".equals(mode)) {
                return RetrieveMode.EXPLORE;
            }
        }
        if (taskType == TaskType.SIMILAR_RETRIEVAL || isExploreIntent(query)) {
            return RetrieveMode.EXPLORE;
        }
        if (hasText(artifactId)) {
            return RetrieveMode.STRICT;
        }
        if (taskType == TaskType.OBJECT_IDENTIFICATION || taskType == TaskType.FACT_QA || taskType == TaskType.DETAIL_ANALYSIS) {
            return RetrieveMode.STRICT;
        }
        return RetrieveMode.EXPLORE;
    }

    private boolean isExploreIntent(String query) {
        if (!hasText(query)) {
            return false;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return containsAny(normalized, "鐩镐技", "绫讳技", "鍚岀被", "鍚屾", "similar", "like");
    }

    @Override
    // 返回工具注册名称。
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    // 返回工具的用途描述。
    public String getDescription() {
        return "Museum multimodal retrieval tool. It classifies task then retrieves text and images.";
    }

    @Override
    // 声明工具类型（固定工具）。
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "Musem multimodal retrieval. Params: kbsId, query, optional artifactId, optional retrieveMode(strict|explore|auto). Returns text hits, image policy and image candidates."
    )
    // 执行多模态知识检索并输出结构化证据与回答策略。
    public String knowledgeQuery(String kbsId, String query, String artifactId, String retrieveMode) {
        TaskType taskType = classify(query);
        String resolvedArtifactId = resolveArtifactId(query, artifactId);
        RetrieveMode resolvedRetrieveMode = resolveRetrieveMode(retrieveMode, query, taskType, resolvedArtifactId);
        boolean strictMode = resolvedRetrieveMode == RetrieveMode.STRICT;
        RiskLevel riskLevel = assessRiskLevel(query);
        if (riskLevel == RiskLevel.BLOCK) {
            return buildRiskBlockedResult(taskType, resolvedArtifactId);
        }
        boolean multiImageOutput = taskType == TaskType.SIMILAR_RETRIEVAL;
        int textTopK = textTopK(taskType);
        int imageTopK = imageTopK(taskType);
        boolean disableImageRetrieve = shouldDisableImageRetrieve(query);
        String imageRetrieveError = "";

        List<ChunkBgeM3> textChunks = new ArrayList<>();
        if (shouldBoostFeatureSearch(query, taskType)) {
            textChunks.addAll(retrieveFeatureRichTextChunks(kbsId, query, resolvedArtifactId, taskType));
        }
        textChunks.addAll(retrieveTextChunks(kbsId, query, resolvedArtifactId, taskType));
        textChunks = prioritizeChunkEvidence(dedupeChunks(textChunks), query, taskType);
        if (textTopK > 0 && textChunks.size() > textTopK) {
            textChunks = textChunks.subList(0, textTopK);
        }
        List<String> textHits = toTextHits(textChunks);
        Set<String> evidenceDocIds = collectDocIds(textChunks);

        List<ImageEmbedding> imageHits = List.of();
        if (!disableImageRetrieve && (!strictMode || !evidenceDocIds.isEmpty())) {
            try {
                imageHits = imageRagService.retrieveByText(kbsId, query, Math.max(1, imageTopK));
                if (imageTopK > 0 && imageHits.size() > imageTopK) {
                    imageHits = imageHits.subList(0, imageTopK);
                }
                imageHits = filterByDistance(imageHits);
                if (strictMode) {
                    imageHits = filterBySourceDocumentIds(imageHits, evidenceDocIds);
                }
            } catch (Exception e) {
                imageHits = List.of();
                imageRetrieveError = e.getMessage() == null ? "unknown" : e.getMessage();
            }
        }

        boolean exactMatch = hasStrongTextEvidence(query, textHits);
        String imagePolicy = exactMatch ? "exact_match" : "similar_only";

        StringBuilder out = new StringBuilder();
        out.append("taskType=").append(taskType.code).append("\n");
        out.append("artifactId=").append(safe(resolvedArtifactId, "none")).append("\n");
        out.append("retrieveMode=").append(resolvedRetrieveMode.code).append("\n");
        out.append("imageRetrieveEnabled=").append(!disableImageRetrieve).append("\n");
        out.append("imagePolicy=").append(imagePolicy).append("\n");
        out.append("imageMatchMode=").append(strictMode ? "doc_linked_strict" : "semantic_explore").append("\n");
        out.append("imageEvidenceLevel=").append(exactMatch ? "exact" : "similar").append("\n");
        out.append("imageDistanceThreshold=").append(maxImageDistance).append("\n");
        if (!imageRetrieveError.isBlank()) {
            out.append("imageRetrieveError=").append(imageRetrieveError).append("\n");
        }
        out.append("textHits=");
        if (textHits.isEmpty()) {
            out.append("none\n");
        } else {
            out.append("\n");
            for (int i = 0; i < textHits.size(); i++) {
                out.append(i + 1).append(". ").append(textHits.get(i)).append("\n");
            }
        }

        out.append("imageHits=");
        if (imageHits.isEmpty()) {
            out.append("none\n");
        } else {
            out.append("\n");
            List<String> markdownLines = new ArrayList<>();
            for (int i = 0; i < imageHits.size(); i++) {
                ImageEmbedding hit = imageHits.get(i);
                String imageUrl = publicBaseUrl + "/api/rag/images/content/" + hit.getId();
                String markdown = "![" + safe(hit.getFileName(), "museum-image-" + (i + 1)) + "](" + imageUrl + ")";
                boolean includeMarkdown = i == 0 || (exactMatch && multiImageOutput);
                if (includeMarkdown) {
                    markdownLines.add(markdown);
                }
                out.append(i + 1)
                        .append(". imageId=").append(hit.getId())
                        .append(", fileName=").append(safe(hit.getFileName(), "unknown"))
                        .append(", distance=").append(hit.getDistance())
                        .append(", imageUrl=").append(imageUrl);
                if (includeMarkdown) {
                    out.append(", markdown=").append(markdown);
                }
                out.append("\n");
            }
            if (!markdownLines.isEmpty()) {
                out.append("primaryImage=").append(markdownLines.get(0)).append("\n");
                if (multiImageOutput && exactMatch) {
                    out.append("imageMarkdownList=").append(String.join(" ", markdownLines)).append("\n");
                }
                if (!exactMatch) {
                    out.append("similarImageNotice=当前未确认目标文物同名命中，已默认返回首图供参考。\n");
                }
            } else {
                out.append("similarImageNotice=当前未确认目标文物同名命中，以下图片仅为相似文物候选，请勿当作目标文物主图。\n");
            }
        }

        if (textHits.isEmpty()) {
            out.append("retrievalStatus=no_text_evidence\n");
            out.append("noEvidencePolicy=No reliable evidence for the target artifact was retrieved. ")
                    .append("Do not fill missing details from background knowledge.\n");
        } else {
            out.append("retrievalStatus=ok\n");
        }

        out.append("answerPolicy=");
        switch (taskType) {
            case FACT_QA -> out.append("Facts first; add one primary image only when imagePolicy=exact_match.");
            case OBJECT_IDENTIFICATION -> out.append("Show primary image, then name and brief intro.");
            case SIMILAR_RETRIEVAL -> out.append("Return multiple images and explain similarity.");
            case DETAIL_ANALYSIS -> out.append("Use text + image details together.");
            case COMPARISON_QA -> out.append("Retrieve two objects and compare with images.");
        }
        return out.toString().trim();
    }

    // 根据任务类型检索文本候选片段。
    private List<String> retrieveTextHits(String kbsId, String query, String artifactId, TaskType taskType) {
        int limit = switch (taskType) {
            case FACT_QA -> 4;
            case OBJECT_IDENTIFICATION -> 3;
            case SIMILAR_RETRIEVAL -> 4;
            case DETAIL_ANALYSIS -> 6;
            case COMPARISON_QA -> 6;
        };
        int candidateLimit = Math.max(limit + 3, 6);
        List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbsId, query, candidateLimit, artifactId);
        List<String> hits = new ArrayList<>();
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            hits.add(chunk.getContent());
        }
        return hits;
    }

    // 使用特征增强的查询变体进行多轮文本召回。
    private List<String> retrieveFeatureRichTextHits(String kbsId, String query, String artifactId, TaskType taskType) {
        int limit = switch (taskType) {
            case FACT_QA -> 5;
            case OBJECT_IDENTIFICATION -> 4;
            case SIMILAR_RETRIEVAL -> 4;
            case DETAIL_ANALYSIS -> 8;
            case COMPARISON_QA -> 8;
        };
        int candidateLimit = Math.max(limit + 4, 8);
        List<String> queryVariants = buildFeatureQueryVariants(query, artifactId);
        List<String> hits = new ArrayList<>();
        for (String variant : queryVariants) {
            List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbsId, variant, candidateLimit, artifactId);
            for (ChunkBgeM3 chunk : chunks) {
                if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                    continue;
                }
                hits.add(chunk.getContent());
            }
        }
        return prioritizeEvidence(dedupe(hits), query, taskType);
    }

    // 构造用于特征检索的查询改写变体列表。
    private List<String> buildFeatureQueryVariants(String query, String artifactId) {
        List<String> variants = new ArrayList<>();
        String base = query == null ? "" : query.trim();
        if (!base.isBlank()) {
            variants.add(base + " 特点 工艺 造型 纹饰 价值 描述 features value_points description");
            variants.add(base + " features value_points description");
        }
        if (artifactId != null && !artifactId.isBlank()) {
            String normalizedArtifactId = artifactId.trim().toLowerCase(Locale.ROOT);
            variants.add(normalizedArtifactId + " 特点 工艺 造型 纹饰 价值 描述 features value_points description");
            if (!base.isBlank()) {
                variants.add(normalizedArtifactId + " " + base);
            }
        }
        if (variants.isEmpty()) {
            variants.add("特点 工艺 造型 纹饰 价值 描述 features value_points description");
        }
        return variants;
    }

    // 按证据强弱对命中片段重新排序。
    private List<String> prioritizeEvidence(List<String> hits, String query, TaskType taskType) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<String> ranked = new ArrayList<>(hits);
        ranked.sort((left, right) -> {
            int scoreRight = evidenceScore(right, query, taskType);
            int scoreLeft = evidenceScore(left, query, taskType);
            if (scoreRight != scoreLeft) {
                return Integer.compare(scoreRight, scoreLeft);
            }
            int lengthRight = right == null ? 0 : right.length();
            int lengthLeft = left == null ? 0 : left.length();
            return Integer.compare(lengthRight, lengthLeft);
        });
        return ranked;
    }

    // 为单条证据计算启发式相关性分数。
    private int evidenceScore(String content, String query, TaskType taskType) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;

        if (containsAny(normalized, "features", "value_points", "description", "qa_pairs")) {
            score += 6;
        }
        score += countKeywordScore(normalized, "特点", "特征", "工艺", "造型", "纹饰", "价值", "描述", "介绍", "历史", "艺术", "代表", "收藏", "产地");

        if (query != null && !query.isBlank()) {
            String q = query.toLowerCase(Locale.ROOT);
            if (containsAny(q, "特点", "特征", "工艺", "造型", "纹饰", "价值", "描述", "介绍")) {
                score += countKeywordScore(normalized, "特点", "特征", "工艺", "造型", "纹饰", "价值", "描述", "介绍");
            }
        }

        if (taskType == TaskType.DETAIL_ANALYSIS) {
            score += 3;
        }
        if (normalized.length() > 180) {
            score += 1;
        }
        if (normalized.length() > 320) {
            score += 1;
        }
        return score;
    }

    // 统计文本中命中关键词的数量得分。
    private int countKeywordScore(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.length == 0) {
            return 0;
        }
        int score = 0;
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    // 判断当前问题是否需要启用特征增强检索。
    private boolean shouldBoostFeatureSearch(String query, TaskType taskType) {
        if (taskType == TaskType.DETAIL_ANALYSIS) {
            return true;
        }
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return q.contains("特点")
                || q.contains("特征")
                || q.contains("工艺")
                || q.contains("造型")
                || q.contains("纹饰")
                || q.contains("价值")
                || q.contains("风格");
    }

    // 构造单条特征增强查询文本（保留作扩展使用）。
    private String buildFeatureBoostedQuery(String query) {
        String base = query == null ? "" : query.trim();
        if (base.isBlank()) {
            return "特点 工艺 造型 纹饰 价值 描述";
        }
        return base + " 特点 工艺 造型 纹饰 价值 描述";
    }

    // 对文本命中结果进行去重。
    private List<String> dedupe(List<String> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<String> deduped = new ArrayList<>();
        for (String hit : hits) {
            if (hit == null || hit.isBlank()) {
                continue;
            }
            boolean exists = false;
            for (String existing : deduped) {
                if (existing.equals(hit)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                deduped.add(hit);
            }
        }
        return deduped;
    }

    // 判断查询是否显式要求禁用图像检索。
    private boolean shouldDisableImageRetrieve(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return q.contains("不要调用 imageknowledgetool")
                || q.contains("仅调用文本知识库工具")
                || q.contains("只调用文本知识库工具");
    }

    // 解析并归一化目标文物 ID。
    private String resolveArtifactId(String query, String artifactId) {
        if (artifactId != null && !artifactId.isBlank()) {
            return artifactId.trim().toLowerCase(Locale.ROOT);
        }
        if (query == null || query.isBlank()) {
            return "";
        }
        var matcher = ARTIFACT_ID_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    // 判断文本证据中是否存在目标实体的强匹配信号。
    private boolean hasStrongTextEvidence(String query, List<String> textHits) {
        if (query == null || query.isBlank() || textHits == null || textHits.isEmpty()) {
            return false;
        }
        String entity = normalizeEntityQuery(query);
        if (entity.length() >= 2) {
            for (String hit : textHits) {
                if (hit != null && hit.contains(entity)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 对用户问题做实体化清洗，提取核心名词。
    private String normalizeEntityQuery(String query) {
        String normalized = query.trim();
        normalized = NON_ENTITY_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("[\\p{Punct}\\p{IsPunctuation}，。！？；：“”‘’（）()《》【】\\s]+", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    // 将查询归类到任务类型以驱动后续检索策略。
    private RiskLevel assessRiskLevel(String query) {
        if (query == null || query.isBlank()) {
            return RiskLevel.SAFE;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (containsAny(q,
                "自杀", "自残", "炸弹", "爆炸物", "投毒", "下毒", "杀人", "袭击",
                "枪支改装", "制作毒品", "制毒", "黑客入侵", "ddos", "勒索软件",
                "how to make a bomb", "how to poison", "how to hack", "kill someone")) {
            return RiskLevel.BLOCK;
        }
        return RiskLevel.SAFE;
    }

    private String buildRiskBlockedResult(TaskType taskType, String artifactId) {
        return """
                taskType=%s
                artifactId=%s
                %s
                riskLevel=high
                riskPolicy=refuse_and_redirect
                retrievalStatus=risk_blocked
                answerPolicy=Refuse harmful or illegal intent. Ask user to switch to safe museum-related questions.
                """.formatted(taskType.code, safe(artifactId, "none"), RISK_BLOCK_MARKER).trim();
    }

    private TaskType classify(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return TaskType.FACT_QA;
        }
        if (containsAny(q, "区别", "对比", "比较", "异同", "不同")) {
            return TaskType.COMPARISON_QA;
        }
        if (containsAny(q, "相似", "类似", "同类", "找和", "同款", "同类型")) {
            return TaskType.SIMILAR_RETRIEVAL;
        }
        if (containsAny(q, "纹饰", "细节", "局部", "铭文", "材质", "工艺", "特征")) {
            return TaskType.DETAIL_ANALYSIS;
        }
        if (containsAny(q, "这是什么", "这件是什么", "识别", "图里", "图片里", "这张图")) {
            return TaskType.OBJECT_IDENTIFICATION;
        }
        return TaskType.FACT_QA;
    }

    // 按任务类型返回文本检索的目标 TopK。
    private int textTopK(TaskType type) {
        return switch (type) {
            case FACT_QA -> 4;
            case OBJECT_IDENTIFICATION -> 2;
            case SIMILAR_RETRIEVAL -> 2;
            case DETAIL_ANALYSIS -> 5;
            case COMPARISON_QA -> 6;
        };
    }

    // 按任务类型返回图像检索的目标 TopK。
    private int imageTopK(TaskType type) {
        return switch (type) {
            case FACT_QA -> 1;
            case OBJECT_IDENTIFICATION -> 1;
            case SIMILAR_RETRIEVAL -> 5;
            case DETAIL_ANALYSIS -> 2;
            case COMPARISON_QA -> 2;
        };
    }

    // 判断文本是否包含任一关键词。
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // 提供空值兜底文案。
    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // 基于距离阈值过滤图像检索结果。
    private List<ImageEmbedding> filterByDistance(List<ImageEmbedding> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ImageEmbedding> filtered = new ArrayList<>();
        for (ImageEmbedding hit : hits) {
            if (hit == null || hit.getDistance() == null) {
                continue;
            }
            if (hit.getDistance() <= maxImageDistance) {
                filtered.add(hit);
            }
        }
        return filtered;
    }

    // 去掉 URL 尾部斜杠并提供默认地址。
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

    private enum TaskType {
        FACT_QA("A.fact_qa"),
        OBJECT_IDENTIFICATION("B.object_identification"),
        SIMILAR_RETRIEVAL("C.similar_retrieval"),
        DETAIL_ANALYSIS("D.detail_analysis"),
        COMPARISON_QA("E.comparison_qa");

        private final String code;

        // 初始化任务类型编码。
        TaskType(String code) {
            this.code = code;
        }
    }

    private enum RetrieveMode {
        STRICT("strict"),
        EXPLORE("explore");

        private final String code;

        RetrieveMode(String code) {
            this.code = code;
        }
    }

    private enum RiskLevel {
        SAFE,
        BLOCK
    }
}
