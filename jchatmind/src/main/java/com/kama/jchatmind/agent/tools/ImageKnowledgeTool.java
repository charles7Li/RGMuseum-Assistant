package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import com.kama.jchatmind.service.ImageRagService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ImageKnowledgeTool implements com.kama.jchatmind.agent.tools.Tool {

    private final ImageRagService imageRagService;
    private final RestClient commonsClient;

    public ImageKnowledgeTool(ImageRagService imageRagService, RestClient.Builder builder) {
        this.imageRagService = imageRagService;
        this.commonsClient = builder.baseUrl("https://commons.wikimedia.org").build();
    }

    @Override
    public String getName() {
        return "ImageKnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "图片检索工具：常规问题走知识库图片召回；导航/出行类问题支持联网搜图。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @Tool(
            name = "ImageKnowledgeTool",
            description = "检索图片。参数：kbsId(知识库ID，常规问答必填)、query(必填)、topK(可选1-8)。规则：导航/出行类查询允许联网搜图；其他查询优先并默认使用知识库图片召回。"
    )
    public String imageQuery(String kbsId, String query, Integer topK) {
        if (!StringUtils.hasText(query)) {
            return "imageStatus=invalid_request\nerror=query cannot be blank";
        }
        int limit = topK == null ? 3 : Math.max(1, Math.min(8, topK));
        String normalizedQuery = query.trim();

        if (isNavigationImageQuery(normalizedQuery)) {
            String webResult = searchFromWeb(normalizedQuery, limit);
            if (!webResult.startsWith("imageStatus=no_results")) {
                return webResult;
            }
            if (StringUtils.hasText(kbsId)) {
                return searchFromKnowledgeBase(kbsId, normalizedQuery, limit);
            }
            return webResult;
        }

        if (!StringUtils.hasText(kbsId)) {
            return "imageStatus=invalid_request\nerror=kbsId is required for knowledge-base image retrieval";
        }
        return searchFromKnowledgeBase(kbsId, normalizedQuery, limit);
    }

    private boolean isNavigationImageQuery(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return containsAny(q,
                "导航", "路线", "出行", "路书", "交通",
                "museum route", "trip plan", "travel route", "navigation");
    }

    private String searchFromKnowledgeBase(String kbsId, String query, int limit) {
        List<ImageEmbedding> hits = imageRagService.retrieveByText(kbsId, query, limit);
        if (hits == null || hits.isEmpty()) {
            return "imageStatus=no_results\nmessage=知识库未检索到相关图片";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            ImageEmbedding hit = hits.get(i);
            String imageUrl = "/api/rag/images/content/" + hit.getId();
            sb.append("source=kb")
                    .append(", imageId=").append(hit.getId())
                    .append(", fileName=").append(hit.getFileName())
                    .append(", imageUrl=").append(imageUrl)
                    .append(", markdown=![").append(hit.getFileName()).append("](").append(imageUrl).append(")");
            if (i < hits.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String searchFromWeb(String query, int limit) {
        Set<String> imageUrls = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        List<String> variants = buildQueryVariants(query);

        for (String variant : variants) {
            List<ImageHit> hits = searchCommons(variant, limit);
            for (ImageHit hit : hits) {
                if (!StringUtils.hasText(hit.imageUrl) || !imageUrls.add(hit.imageUrl)) {
                    continue;
                }
                lines.add("source=web"
                        + ", title=" + hit.title
                        + ", imageUrl=" + hit.imageUrl
                        + ", markdown=![" + hit.title + "](" + hit.imageUrl + ")");
                if (lines.size() >= limit) {
                    return String.join("\n", lines);
                }
            }
        }

        if (lines.isEmpty()) {
            return "imageStatus=no_results\nmessage=联网未检索到可用图片";
        }
        return String.join("\n", lines);
    }

    private List<String> buildQueryVariants(String query) {
        List<String> variants = new ArrayList<>();
        variants.add(query);
        variants.add(query + " 文物");
        variants.add(query + " 博物馆 文物");
        variants.add("Guangzhou Museum artifact " + query);
        return variants;
    }

    private List<ImageHit> searchCommons(String query, int limit) {
        URI uri = UriComponentsBuilder.fromPath("/w/api.php")
                .queryParam("action", "query")
                .queryParam("format", "json")
                .queryParam("generator", "search")
                .queryParam("gsrsearch", query)
                .queryParam("gsrnamespace", 6)
                .queryParam("gsrlimit", Math.max(5, limit))
                .queryParam("prop", "imageinfo")
                .queryParam("iiprop", "url")
                .queryParam("iiurlwidth", 1600)
                .build(true)
                .toUri();

        JsonNode body;
        try {
            body = commonsClient.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            return List.of();
        }
        if (body == null) {
            return List.of();
        }
        JsonNode pages = body.path("query").path("pages");
        if (!pages.isObject()) {
            return List.of();
        }

        List<ImageHit> hits = new ArrayList<>();
        pages.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            String title = page.path("title").asText("");
            JsonNode imageInfo = page.path("imageinfo");
            if (!imageInfo.isArray() || imageInfo.isEmpty()) {
                return;
            }
            JsonNode info = imageInfo.get(0);
            String imageUrl = info.path("thumburl").asText("");
            if (!StringUtils.hasText(imageUrl)) {
                imageUrl = info.path("url").asText("");
            }
            if (!StringUtils.hasText(imageUrl)) {
                return;
            }
            String safeTitle = StringUtils.hasText(title) ? title.replace("File:", "") : "artifact_image";
            hits.add(new ImageHit(safeTitle, imageUrl));
        });
        return hits;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record ImageHit(String title, String imageUrl) {
    }
}

