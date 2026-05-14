package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MuseumTripPlannerTool implements Tool {

    private static final Pattern MUSEUM_PATTERN = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9路()\\-]{2,40}(博物馆|纪念馆|美术馆|展览馆|文化馆|陈列馆|科技馆|故宫|遗址公园|遗址))"
    );
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9路]{2,30}(方尊|鼎|壶|瓶|瓷|玉|簋|觥|佛像|青铜器|文物))"
    );

    private final RagService ragService;
    private final MuseumPlaceSearchTool museumPlaceSearchTool;
    private final ImageKnowledgeTool imageKnowledgeTool;
    private final AirQualityTool airQualityTool;
    private final com.kama.jchatmind.agent.tools.test.WeatherTool weatherTool;
    private final String defaultCity;

    public MuseumTripPlannerTool(
            RagService ragService,
            MuseumPlaceSearchTool museumPlaceSearchTool,
            ImageKnowledgeTool imageKnowledgeTool,
            AirQualityTool airQualityTool,
            com.kama.jchatmind.agent.tools.test.WeatherTool weatherTool,
            @Value("${weather.default-city:广州}") String defaultCity
    ) {
        this.ragService = ragService;
        this.museumPlaceSearchTool = museumPlaceSearchTool;
        this.imageKnowledgeTool = imageKnowledgeTool;
        this.airQualityTool = airQualityTool;
        this.weatherTool = weatherTool;
        this.defaultCity = defaultCity;
    }

    @Override
    public String getName() {
        return "museumTripPlannerTool";
    }

    @Override
    public String getDescription() {
        return "根据目标地点联动天气、空气质量与图片检索，生成博物馆出行与参观建议。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "museumTripPlan",
            description = "生成博物馆出行计划。参数：kbsId(必填)、target(必填)、city/date/timeBudgetMinutes/travelMode/topK(可选)。输出地点线索、路线建议、关键文物、天气空气提醒及图片线索。"
    )
    public String planTrip(
            String kbsId,
            String target,
            String city,
            String date,
            Integer timeBudgetMinutes,
            String travelMode,
            Integer topK
    ) {
        if (kbsId == null || kbsId.isBlank()) {
            return "status=invalid_request\nerror=kbsId 不能为空";
        }
        if (target == null || target.isBlank()) {
            return "status=invalid_request\nerror=target 不能为空";
        }

        String resolvedCity = normalize(city);
        if (resolvedCity.isBlank()) {
            resolvedCity = defaultCity;
        }
        String resolvedDate = normalize(date);
        if (resolvedDate.isBlank()) {
            resolvedDate = LocalDate.now().toString();
        }
        int budget = timeBudgetMinutes == null ? 180 : Math.max(60, Math.min(600, timeBudgetMinutes));
        int recallTopK = topK == null ? 12 : Math.max(1, Math.min(20, topK));
        String mode = normalizeMode(travelMode);

        String placeResult = museumPlaceSearchTool.searchMuseumAndPlaces(kbsId, target, Math.min(10, recallTopK));
        String weatherResult = weatherTool.getWeather(resolvedCity, resolvedDate);
        String airResult = airQualityTool.getAirQuality(resolvedCity, resolvedDate);

        // 导航语境下图片优先走联网；若失败由 ImageKnowledgeTool 内部回退 KB。
        String imageResult = imageKnowledgeTool.imageQuery(
                kbsId,
                target + " 导航 出行 文物 图片",
                3
        );

        List<ChunkBgeM3> chunks = ragService.similaritySearchChunks(kbsId, target, recallTopK);
        Set<String> museums = extractByPattern(chunks, MUSEUM_PATTERN, 6);
        Set<String> artifacts = extractByPattern(chunks, ARTIFACT_PATTERN, 10);
        if (artifacts.isEmpty()) {
            artifacts.addAll(extractFallbackArtifacts(chunks, 6));
        }

        int suggestedDuration = suggestDurationMinutes(mode, artifacts.size());
        int finalDuration = Math.min(Math.max(90, suggestedDuration), budget);
        List<String> itinerary = buildItinerary(new ArrayList<>(artifacts), finalDuration, mode);

        StringBuilder out = new StringBuilder();
        out.append("status=ok\n");
        out.append("target=").append(target.trim()).append("\n");
        out.append("city=").append(resolvedCity).append("\n");
        out.append("date=").append(resolvedDate).append("\n");
        out.append("travelMode=").append(mode).append("\n");
        out.append("timeBudgetMinutes=").append(budget).append("\n");
        out.append("suggestedDurationMinutes=").append(finalDuration).append("\n");
        out.append("museumHints=");
        appendList(out, museums, 6);
        out.append("keyArtifacts=");
        appendList(out, artifacts, 8);
        out.append("itinerary=");
        if (itinerary.isEmpty()) {
            out.append("none\n");
        } else {
            out.append("\n");
            for (int i = 0; i < itinerary.size(); i++) {
                out.append(i + 1).append(". ").append(itinerary.get(i)).append("\n");
            }
        }
        out.append("airSummary=").append(singleLine(airResult)).append("\n");
        out.append("weatherSummary=").append(singleLine(weatherResult)).append("\n");
        out.append("placeSummary=").append(singleLine(placeResult)).append("\n");
        out.append("relatedImages=").append(singleLine(imageResult)).append("\n");
        out.append("travelAdvice=").append(buildTravelAdvice(airResult, weatherResult, mode)).append("\n");
        out.append("answerPolicy=先给时间与路线，再给关键文物与图片；如有 markdown 图片请原样贴出。\n");
        return out.toString().trim();
    }

    private Set<String> extractByPattern(List<ChunkBgeM3> chunks, Pattern pattern, int limit) {
        Set<String> hits = new LinkedHashSet<>();
        if (chunks == null || chunks.isEmpty()) {
            return hits;
        }
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            Matcher matcher = pattern.matcher(chunk.getContent());
            while (matcher.find()) {
                String value = matcher.group(1);
                if (value != null && !value.isBlank()) {
                    hits.add(value.trim());
                }
                if (hits.size() >= limit) {
                    return hits;
                }
            }
        }
        return hits;
    }

    private Set<String> extractFallbackArtifacts(List<ChunkBgeM3> chunks, int limit) {
        Set<String> hits = new LinkedHashSet<>();
        if (chunks == null || chunks.isEmpty()) {
            return hits;
        }
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            List<String> sentences = splitSentences(chunk.getContent());
            for (String sentence : sentences) {
                String normalized = sentence.trim();
                if (normalized.length() < 6 || normalized.length() > 40) {
                    continue;
                }
                if (containsAny(normalized, "文物", "器物", "代表", "藏品", "展品")) {
                    hits.add(normalized);
                }
                if (hits.size() >= limit) {
                    return hits;
                }
            }
        }
        return hits;
    }

    private int suggestDurationMinutes(String mode, int artifactCount) {
        int base = switch (mode) {
            case "relaxed" -> 150;
            case "deep" -> 240;
            default -> 180;
        };
        int perArtifact = switch (mode) {
            case "relaxed" -> 10;
            case "deep" -> 16;
            default -> 12;
        };
        return base + Math.max(0, artifactCount - 3) * perArtifact;
    }

    private List<String> buildItinerary(List<String> artifacts, int duration, String mode) {
        List<String> itinerary = new ArrayList<>();
        int warmup = 20;
        int remaining = Math.max(30, duration - warmup - 15);
        int stopCount = Math.max(1, Math.min(artifacts.size(), "deep".equals(mode) ? 6 : 4));
        int each = Math.max(12, remaining / stopCount);

        itinerary.add("入馆与导览（约" + warmup + "分钟）：完成安检、地图确认与首展厅定位");
        for (int i = 0; i < stopCount; i++) {
            String artifact = artifacts.get(i);
            itinerary.add("重点文物站点 " + (i + 1) + "（约 " + each + " 分钟）：" + trim(artifact, 32));
        }
        itinerary.add("机动与休息（约 15 分钟）");
        return itinerary;
    }

    private String buildTravelAdvice(String air, String weather, String mode) {
        StringBuilder advice = new StringBuilder();
        advice.append("建议提前 20-30 分钟到馆；");
        String lowerAir = air == null ? "" : air.toLowerCase(Locale.ROOT);
        if (containsAny(lowerAir, "中度污染", "重度污染", "严重污染", "aqi=1", "aqi=2", "aqi=3")) {
            advice.append("空气一般，优先室内连贯路线并减少馆外停留；");
        } else {
            advice.append("空气可接受，可按常规路线参观；");
        }
        String lowerWeather = weather == null ? "" : weather.toLowerCase(Locale.ROOT);
        if (containsAny(lowerWeather, "雨", "雷")) {
            advice.append("有降雨风险，建议携带雨具并预留排队时间；");
        }
        if ("deep".equals(mode)) {
            advice.append("深度模式建议中途安排一次短休息。");
        } else {
            advice.append("时间紧可优先关键文物站点。");
        }
        return advice.toString();
    }

    private void appendList(StringBuilder out, Set<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            out.append("none\n");
            return;
        }
        out.append("\n");
        int i = 1;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            out.append(i++).append(". ").append(trim(value, 42)).append("\n");
            if (i > limit) {
                break;
            }
        }
        if (i == 1) {
            out.append("none\n");
        }
    }

    private List<String> splitSentences(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String[] parts = content.split("[。！？；\\n]");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String normalizeMode(String mode) {
        String normalized = normalize(mode).toLowerCase(Locale.ROOT);
        if ("relaxed".equals(normalized) || "deep".equals(normalized)) {
            return normalized;
        }
        return "standard";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String trim(String value, int maxLen) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (maxLen <= 0 || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }

    private String singleLine(String text) {
        if (text == null || text.isBlank()) {
            return "none";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return trim(normalized, 220);
    }
}
