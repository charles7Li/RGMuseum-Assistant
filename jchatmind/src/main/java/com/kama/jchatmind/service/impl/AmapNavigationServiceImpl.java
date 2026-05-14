package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.request.NavigationPlanRequest;
import com.kama.jchatmind.model.response.NavigationPlanResponse;
import com.kama.jchatmind.model.vo.NavigationRouteStepVO;
import com.kama.jchatmind.service.NavigationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class AmapNavigationServiceImpl implements NavigationService {

    private final RestClient restClient;
    private final String amapKey;
    private final String walkingPath;
    private final String drivingPath;

    public AmapNavigationServiceImpl(
            RestClient.Builder builder,
            @Value("${map.amap.base-url:https://restapi.amap.com}") String baseUrl,
            @Value("${map.amap.key:}") String amapKey,
            @Value("${map.amap.walking-path:/v3/direction/walking}") String walkingPath,
            @Value("${map.amap.driving-path:/v3/direction/driving}") String drivingPath
    ) {
        this.restClient = builder.baseUrl(trimTrailingSlash(baseUrl)).build();
        this.amapKey = amapKey;
        this.walkingPath = normalizePath(walkingPath, "/v3/direction/walking");
        this.drivingPath = normalizePath(drivingPath, "/v3/direction/driving");
    }

    @Override
    public NavigationPlanResponse planRoute(NavigationPlanRequest request) {
        if (request == null) {
            throw new BizException("navigation request cannot be null");
        }
        if (!StringUtils.hasText(amapKey)) {
            throw new BizException("map.amap.key is not configured");
        }
        validateCoordinate("originLongitude", request.getOriginLongitude(), -180, 180);
        validateCoordinate("originLatitude", request.getOriginLatitude(), -90, 90);
        validateCoordinate("destinationLongitude", request.getDestinationLongitude(), -180, 180);
        validateCoordinate("destinationLatitude", request.getDestinationLatitude(), -90, 90);

        String mode = normalizeMode(request.getMode());
        String origin = formatLngLat(request.getOriginLongitude(), request.getOriginLatitude());
        String destination = formatLngLat(request.getDestinationLongitude(), request.getDestinationLatitude());
        String path = "driving".equals(mode) ? drivingPath : walkingPath;

        URI uri = UriComponentsBuilder.fromPath(path)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("key", amapKey)
                .build(true)
                .toUri();

        JsonNode body;
        try {
            body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new BizException("map route request failed: " + e.getMessage());
        }
        if (body == null) {
            throw new BizException("empty map route response");
        }
        if (!"1".equals(body.path("status").asText())) {
            String info = body.path("info").asText("unknown_error");
            String infocode = body.path("infocode").asText("unknown_code");
            throw new BizException("map route failed: " + info + " (infocode=" + infocode + ")");
        }

        JsonNode pathsNode = body.path("route").path("paths");
        if (!pathsNode.isArray() || pathsNode.isEmpty()) {
            throw new BizException("map route response has no paths");
        }

        JsonNode mainPath = pathsNode.get(0);
        List<NavigationRouteStepVO> steps = parseSteps(mainPath.path("steps"));
        String polyline = mergePolyline(steps);
        double distance = toDouble(mainPath.get("distance"));
        double duration = toDouble(mainPath.get("duration"));
        String destinationName = StringUtils.hasText(request.getDestinationName())
                ? request.getDestinationName().trim()
                : destination;

        return NavigationPlanResponse.builder()
                .provider("amap")
                .mode(mode)
                .origin(origin)
                .destination(destinationName)
                .distanceMeters(distance)
                .durationSeconds(duration)
                .overview(buildOverview(mode, distance, duration))
                .polyline(polyline)
                .steps(steps.toArray(new NavigationRouteStepVO[0]))
                .build();
    }

    private List<NavigationRouteStepVO> parseSteps(JsonNode stepsNode) {
        List<NavigationRouteStepVO> steps = new ArrayList<>();
        if (!stepsNode.isArray()) {
            return steps;
        }
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode node = stepsNode.get(i);
            if (node == null || node.isMissingNode()) {
                continue;
            }
            String instruction = text(node, "instruction");
            String road = text(node, "road");
            if (!StringUtils.hasText(road)) {
                road = text(node, "assistant_action");
            }
            steps.add(NavigationRouteStepVO.builder()
                    .index(i + 1)
                    .instruction(instruction)
                    .road(road)
                    .distanceMeters(toDouble(node.get("distance")))
                    .durationSeconds(toDouble(node.get("duration")))
                    .polyline(text(node, "polyline"))
                    .build());
        }
        return steps;
    }

    private String mergePolyline(List<NavigationRouteStepVO> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (NavigationRouteStepVO step : steps) {
            if (step == null || !StringUtils.hasText(step.getPolyline())) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(";");
            }
            out.append(step.getPolyline());
        }
        return out.toString();
    }

    private String buildOverview(String mode, double distanceMeters, double durationSeconds) {
        String modeText = "driving".equals(mode) ? "驾车" : "步行";
        String km = String.format(Locale.US, "%.2f", Math.max(0D, distanceMeters) / 1000D);
        long minutes = Math.max(1L, Math.round(Math.max(0D, durationSeconds) / 60D));
        return "%s路线约 %s 公里，预计 %d 分钟".formatted(modeText, km, minutes);
    }

    private String text(JsonNode node, String key) {
        if (node == null || !node.has(key)) {
            return "";
        }
        String value = node.path(key).asText("");
        return value == null ? "" : value.trim();
    }

    private double toDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0D;
        }
        if (node.isNumber()) {
            return node.asDouble(0D);
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (Exception ignored) {
                return 0D;
            }
        }
        return 0D;
    }

    private String formatLngLat(Double longitude, Double latitude) {
        return String.format(Locale.US, "%.6f,%.6f", longitude, latitude);
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
            case "driving", "walk", "walking" -> "driving".equals(normalized) ? "driving" : "walking";
            default -> throw new BizException("mode must be walking or driving");
        };
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
}
