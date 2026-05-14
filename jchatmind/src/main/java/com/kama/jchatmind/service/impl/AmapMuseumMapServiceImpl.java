package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.request.MuseumMapPlanRequest;
import com.kama.jchatmind.model.request.NavigationPlanRequest;
import com.kama.jchatmind.model.response.MuseumMapPlanResponse;
import com.kama.jchatmind.model.response.NavigationPlanResponse;
import com.kama.jchatmind.model.vo.MuseumRouteCandidateVO;
import com.kama.jchatmind.service.MuseumMapService;
import com.kama.jchatmind.service.NavigationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AmapMuseumMapServiceImpl implements MuseumMapService {

    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[-~至]\\s*(\\d{1,2}:\\d{2})");

    private final RestClient restClient;
    private final NavigationService navigationService;
    private final String amapKey;
    private final String placeTextPath;
    private final String placeDetailPath;

    public AmapMuseumMapServiceImpl(
            RestClient.Builder builder,
            NavigationService navigationService,
            @Value("${map.amap.base-url:https://restapi.amap.com}") String baseUrl,
            @Value("${map.amap.key:}") String amapKey,
            @Value("${map.amap.place-text-path:/v3/place/text}") String placeTextPath,
            @Value("${map.amap.place-detail-path:/v3/place/detail}") String placeDetailPath
    ) {
        this.restClient = builder.baseUrl(trimTrailingSlash(baseUrl)).build();
        this.navigationService = navigationService;
        this.amapKey = amapKey;
        this.placeTextPath = normalizePath(placeTextPath, "/v3/place/text");
        this.placeDetailPath = normalizePath(placeDetailPath, "/v3/place/detail");
    }

    @Override
    public MuseumMapPlanResponse searchMuseumsAndPlan(MuseumMapPlanRequest request) {
        if (request == null) {
            throw new BizException("museum map request cannot be null");
        }
        if (!StringUtils.hasText(amapKey)) {
            throw new BizException("map.amap.key is not configured");
        }
        validateCoordinate("originLongitude", request.getOriginLongitude(), -180, 180);
        validateCoordinate("originLatitude", request.getOriginLatitude(), -90, 90);
        if (!StringUtils.hasText(request.getTarget())) {
            throw new BizException("target cannot be blank");
        }

        String mode = normalizeMode(request.getMode());
        String target = request.getTarget().trim();
        String city = request.getCity() == null ? "" : request.getCity().trim();
        int poiLimit = clamp(request.getPoiLimit(), 1, 20, 8);
        int routeLimit = clamp(request.getRouteLimit(), 1, 10, 3);
        boolean cityLimit = request.getCityLimit() == null || request.getCityLimit();
        String keywords = buildMuseumKeywords(target);

        List<SearchPoi> pois = searchMuseumPois(keywords, city, cityLimit, poiLimit);
        if (pois.isEmpty()) {
            return MuseumMapPlanResponse.builder()
                    .provider("amap")
                    .target(target)
                    .city(city)
                    .mode(mode)
                    .searchKeywords(keywords)
                    .candidateCount(0)
                    .plannedCount(0)
                    .candidates(new MuseumRouteCandidateVO[0])
                    .summary("未检索到博物馆候选，请尝试更具体的地点关键词")
                    .build();
        }

        List<MuseumRouteCandidateVO> candidates = new ArrayList<>();
        for (SearchPoi poi : pois) {
            JsonNode detail = fetchPoiDetail(poi.id);
            String openTime = resolveOpenTime(poi.rawNode, detail);
            String closingTime = extractClosingTime(openTime);
            String businessStatus = resolveBusinessStatus(poi.rawNode, detail, openTime);

            NavigationPlanResponse route = null;
            String routeError = null;
            try {
                route = planRoute(request.getOriginLongitude(), request.getOriginLatitude(), poi, mode);
            } catch (Exception e) {
                routeError = e.getMessage();
            }

            MuseumRouteCandidateVO.MuseumRouteCandidateVOBuilder builder = MuseumRouteCandidateVO.builder()
                    .poiId(poi.id)
                    .museumName(poi.name)
                    .address(poi.address)
                    .longitude(poi.longitude)
                    .latitude(poi.latitude)
                    .openTime(openTime)
                    .closingTime(closingTime)
                    .businessStatus(businessStatus)
                    .routeError(routeError);

            if (route != null) {
                builder.distanceMeters(route.getDistanceMeters())
                        .durationSeconds(route.getDurationSeconds())
                        .overview(route.getOverview())
                        .polyline(route.getPolyline())
                        .steps(route.getSteps());
            }
            candidates.add(builder.build());
        }

        candidates.sort(Comparator.comparing(
                MuseumRouteCandidateVO::getDistanceMeters,
                Comparator.nullsLast(Double::compareTo)
        ));

        if (candidates.size() > routeLimit) {
            candidates = new ArrayList<>(candidates.subList(0, routeLimit));
        }

        int plannedCount = (int) candidates.stream().filter(it -> it.getDistanceMeters() != null).count();
        String summary = "共检索到 %d 个候选博物馆，成功规划 %d 条路线".formatted(pois.size(), plannedCount);

        return MuseumMapPlanResponse.builder()
                .provider("amap")
                .target(target)
                .city(city)
                .mode(mode)
                .searchKeywords(keywords)
                .candidateCount(pois.size())
                .plannedCount(plannedCount)
                .candidates(candidates.toArray(new MuseumRouteCandidateVO[0]))
                .summary(summary)
                .build();
    }

    private List<SearchPoi> searchMuseumPois(String keywords, String city, boolean cityLimit, int limit) {
        URI uriBuilder = UriComponentsBuilder.fromPath(placeTextPath)
                .queryParam("key", amapKey)
                .queryParam("keywords", keywords)
                .queryParam("offset", limit)
                .queryParam("page", 1)
                .queryParam("extensions", "all")
                .queryParam("citylimit", cityLimit)
                .queryParamIfPresent("city", StringUtils.hasText(city) ? java.util.Optional.of(city) : java.util.Optional.empty())
                .build(true)
                .toUri();

        JsonNode body;
        try {
            body = restClient.get().uri(uriBuilder).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new BizException("museum place search failed: " + e.getMessage());
        }
        ensureSuccess(body, "museum place search");

        JsonNode poisNode = body.path("pois");
        List<SearchPoi> pois = new ArrayList<>();
        if (!poisNode.isArray()) {
            return pois;
        }
        for (JsonNode poiNode : poisNode) {
            String id = text(poiNode, "id");
            String name = text(poiNode, "name");
            String location = text(poiNode, "location");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(name) || !StringUtils.hasText(location)) {
                continue;
            }
            double[] lngLat = parseLngLat(location);
            if (lngLat == null) {
                continue;
            }
            String address = buildAddress(poiNode);
            pois.add(new SearchPoi(id, name, address, lngLat[0], lngLat[1], poiNode));
            if (pois.size() >= limit) {
                break;
            }
        }
        return pois;
    }

    private JsonNode fetchPoiDetail(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        URI uriBuilder = UriComponentsBuilder.fromPath(placeDetailPath)
                .queryParam("key", amapKey)
                .queryParam("id", id)
                .queryParam("extensions", "all")
                .build(true)
                .toUri();
        JsonNode body;
        try {
            body = restClient.get().uri(uriBuilder).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            return null;
        }
        if (body == null || !"1".equals(body.path("status").asText())) {
            return null;
        }
        JsonNode pois = body.path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return null;
        }
        return pois.get(0);
    }

    private NavigationPlanResponse planRoute(Double originLng, Double originLat, SearchPoi poi, String mode) {
        NavigationPlanRequest request = new NavigationPlanRequest();
        request.setOriginLongitude(originLng);
        request.setOriginLatitude(originLat);
        request.setDestinationLongitude(poi.longitude);
        request.setDestinationLatitude(poi.latitude);
        request.setDestinationName(poi.name);
        request.setMode(mode);
        return navigationService.planRoute(request);
    }

    private String resolveOpenTime(JsonNode poiNode, JsonNode detailNode) {
        String value = firstNonBlank(
                nestedText(detailNode, "business", "opentime_today"),
                nestedText(detailNode, "business", "open_time"),
                nestedText(detailNode, "biz_ext", "opentime"),
                nestedText(detailNode, "biz_ext", "open_time"),
                text(detailNode, "business_hours"),
                nestedText(poiNode, "business", "opentime_today"),
                nestedText(poiNode, "biz_ext", "opentime"),
                nestedText(poiNode, "biz_ext", "open_time")
        );
        return StringUtils.hasText(value) ? value : "unknown";
    }

    private String resolveBusinessStatus(JsonNode poiNode, JsonNode detailNode, String openTime) {
        String status = firstNonBlank(
                nestedText(detailNode, "business", "business_status"),
                nestedText(poiNode, "business", "business_status"),
                text(detailNode, "business_status")
        );
        if (StringUtils.hasText(status)) {
            return status;
        }
        return StringUtils.hasText(openTime) && !"unknown".equalsIgnoreCase(openTime) ? "hours_provided" : "unknown";
    }

    private String extractClosingTime(String openTime) {
        if (!StringUtils.hasText(openTime)) {
            return "unknown";
        }
        Matcher matcher = TIME_RANGE_PATTERN.matcher(openTime);
        String closing = null;
        while (matcher.find()) {
            closing = matcher.group(2);
        }
        return StringUtils.hasText(closing) ? closing : "unknown";
    }

    private String buildMuseumKeywords(String target) {
        String normalized = target.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("博物馆") || lower.contains("纪念馆") || lower.contains("美术馆") || lower.contains("museum")) {
            return normalized;
        }
        return normalized + " 博物馆";
    }

    private String buildAddress(JsonNode poiNode) {
        String address = text(poiNode, "address");
        String adName = text(poiNode, "adname");
        String cityName = text(poiNode, "cityname");
        String province = text(poiNode, "pname");
        String merged = String.join(" ", List.of(province, cityName, adName, address)).trim().replaceAll("\\s{2,}", " ");
        return StringUtils.hasText(merged) ? merged : "unknown";
    }

    private String text(JsonNode node, String key) {
        if (node == null || !node.has(key)) {
            return "";
        }
        String value = node.path(key).asText("");
        return value == null ? "" : value.trim();
    }

    private String nestedText(JsonNode node, String objectKey, String fieldKey) {
        if (node == null || !node.has(objectKey)) {
            return "";
        }
        JsonNode object = node.path(objectKey);
        if (object == null || object.isMissingNode()) {
            return "";
        }
        String value = object.path(fieldKey).asText("");
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private void ensureSuccess(JsonNode body, String action) {
        if (body == null) {
            throw new BizException(action + " response is empty");
        }
        if (!"1".equals(body.path("status").asText())) {
            String info = body.path("info").asText("unknown_error");
            String infocode = body.path("infocode").asText("unknown_code");
            throw new BizException(action + " failed: " + info + " (infocode=" + infocode + ")");
        }
    }

    private double[] parseLngLat(String location) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception ignored) {
            return null;
        }
    }

    private void validateCoordinate(String name, Double value, double min, double max) {
        if (value == null || value.isNaN() || value < min || value > max) {
            throw new BizException(name + " is invalid");
        }
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "walking";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "walk", "walking" -> "walking";
            case "drive", "driving" -> "driving";
            default -> throw new BizException("mode must be walking or driving");
        };
    }

    private int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String normalizePath(String value, String defaultPath) {
        if (!StringUtils.hasText(value)) {
            return defaultPath;
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private String trimTrailingSlash(String url) {
        String safe = StringUtils.hasText(url) ? url.trim() : "https://restapi.amap.com";
        while (safe.endsWith("/")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe;
    }

    private record SearchPoi(
            String id,
            String name,
            String address,
            double longitude,
            double latitude,
            JsonNode rawNode
    ) {
    }
}

