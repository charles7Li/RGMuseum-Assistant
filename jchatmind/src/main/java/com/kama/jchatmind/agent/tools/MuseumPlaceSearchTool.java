package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MuseumPlaceSearchTool implements Tool {

    private static final Pattern MUSEUM_NAME_PATTERN = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9·（）()\\-]{2,40}(博物馆|纪念馆|美术馆|展览馆|文化馆|陈列馆|科技馆|故宫|遗址公园|遗址))"
    );
    private static final Pattern LOCATION_HINT_PATTERN = Pattern.compile(
            "(位于|坐落于|地处|地址|地点)\\s*[:：]?\\s*([^，。；;\\n]{2,80})"
    );

    private final RagService ragService;

    public MuseumPlaceSearchTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "MuseumPlaceSearchTool";
    }

    @Override
    public String getDescription() {
        return "根据目标地点或文物关键词，检索相关博物馆与地点线索。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "MuseumPlaceSearchTool",
            description = "搜索目标地点对应的博物馆及相关地点。参数：kbsId(知识库ID，必填)、target(目标地点/文物/主题，必填)、topK(召回候选数，默认8，范围1-20)。返回 museumHits、relatedPlaces、evidenceSnippets。"
    )
    public String searchMuseumAndPlaces(String kbsId, String target, Integer topK) {
        if (kbsId == null || kbsId.isBlank()) {
            return "retrievalStatus=invalid_request\nerror=kbsId 不能为空";
        }
        if (target == null || target.isBlank()) {
            return "retrievalStatus=invalid_request\nerror=target 不能为空";
        }

        int recallTopK = topK == null ? 8 : Math.max(1, Math.min(20, topK));
        List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbsId, target.trim(), recallTopK);
        if (chunks == null || chunks.isEmpty()) {
            return """
                    target=%s
                    retrievalStatus=no_evidence
                    museumHits=none
                    relatedPlaces=none
                    evidenceSnippets=none
                    """.formatted(target.trim()).trim();
        }

        Set<String> museumHits = new LinkedHashSet<>();
        Set<String> relatedPlaces = new LinkedHashSet<>();
        List<String> snippets = new ArrayList<>();

        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            String content = normalizeText(chunk.getContent());
            if (content.isBlank()) {
                continue;
            }

            if (snippets.size() < 6) {
                snippets.add(trimSnippet(content, 120));
            }

            Matcher museumMatcher = MUSEUM_NAME_PATTERN.matcher(content);
            while (museumMatcher.find()) {
                String hit = museumMatcher.group(1);
                if (hit != null && !hit.isBlank()) {
                    museumHits.add(hit.trim());
                }
            }

            Matcher locationMatcher = LOCATION_HINT_PATTERN.matcher(content);
            while (locationMatcher.find()) {
                String place = locationMatcher.group(2);
                if (place != null && !place.isBlank()) {
                    relatedPlaces.add(place.trim());
                }
            }

            for (String sentence : splitSentences(content)) {
                if (containsPlaceKeyword(sentence)) {
                    relatedPlaces.add(trimSnippet(sentence, 60));
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("target=").append(target.trim()).append("\n");
        out.append("retrievalStatus=ok\n");
        out.append("museumHits=");
        appendListOrNone(out, museumHits, 8);
        out.append("relatedPlaces=");
        appendListOrNone(out, relatedPlaces, 10);
        out.append("evidenceSnippets=");
        if (snippets.isEmpty()) {
            out.append("none\n");
        } else {
            out.append("\n");
            for (int i = 0; i < snippets.size(); i++) {
                out.append(i + 1).append(". ").append(snippets.get(i)).append("\n");
            }
        }
        out.append("answerPolicy=优先基于 museumHits 与 relatedPlaces 回答路径/地点问题；若信息不足需明确说明。");
        return out.toString().trim();
    }

    private void appendListOrNone(StringBuilder out, Set<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            out.append("none\n");
            return;
        }
        out.append("\n");
        int index = 1;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            out.append(index++).append(". ").append(value).append("\n");
            if (index > limit) {
                break;
            }
        }
        if (index == 1) {
            out.append("none\n");
        }
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private List<String> splitSentences(String content) {
        String[] parts = content.split("[。！？!?；;\\n]");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                sentences.add(normalized);
            }
        }
        return sentences;
    }

    private boolean containsPlaceKeyword(String sentence) {
        String s = sentence.toLowerCase(Locale.ROOT);
        return s.contains("博物馆")
                || s.contains("纪念馆")
                || s.contains("展厅")
                || s.contains("遗址")
                || s.contains("位于")
                || s.contains("坐落")
                || s.contains("地址")
                || s.contains("location");
    }

    private String trimSnippet(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}

