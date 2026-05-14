package com.kama.jchatmind.agent.tools;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AirQualityTool implements Tool {

    private final RestClient restClient;

    public AirQualityTool(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public String getName() {
        return "airQualityTool";
    }

    @Override
    public String getDescription() {
        return "查询城市空气质量（AQI/PM2.5）并返回出行建议。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "airQuality",
            description = "查询空气质量。参数：city(城市，必填)、date(可选，yyyy-MM-dd，默认今天)。返回 AQI、PM2.5、空气等级与出行建议。"
    )
    public String getAirQuality(String city, String date) {
        String normalizedCity = normalizeParam(city);
        if (normalizedCity == null || normalizedCity.isBlank()) {
            return "error=city 不能为空";
        }
        try {
            GeoResult geo = geocode(normalizedCity);
            if (geo == null) {
                return "error=未找到城市:" + normalizedCity;
            }
            LocalDate targetDate = parseDate(date);
            URI uri = UriComponentsBuilder
                    .fromHttpUrl("https://air-quality-api.open-meteo.com/v1/air-quality")
                    .queryParam("latitude", geo.latitude())
                    .queryParam("longitude", geo.longitude())
                    .queryParam("hourly", "pm2_5,pm10,us_aqi")
                    .queryParam("timezone", "Asia/Shanghai")
                    .queryParam("start_date", targetDate)
                    .queryParam("end_date", targetDate)
                    .build(true)
                    .toUri();

            Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
            if (body == null || !(body.get("hourly") instanceof Map<?, ?> hourly)) {
                return "city=%s\ndate=%s\nstatus=no_data".formatted(geo.name(), targetDate);
            }

            Double pm25 = median(firstN(hourly.get("pm2_5"), 24));
            Double pm10 = median(firstN(hourly.get("pm10"), 24));
            Double aqi = median(firstN(hourly.get("us_aqi"), 24));

            String level = airLevel(aqi);
            String advice = airAdvice(aqi, pm25);

            return """
                    city=%s
                    date=%s
                    aqi=%s
                    pm2_5=%s
                    pm10=%s
                    level=%s
                    advice=%s
                    """.formatted(
                    geo.name(),
                    targetDate,
                    formatNumber(aqi),
                    formatNumber(pm25),
                    formatNumber(pm10),
                    level,
                    advice
            ).trim();
        } catch (Exception e) {
            return "error=空气质量查询失败:" + e.getMessage();
        }
    }

    private GeoResult geocode(String city) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://geocoding-api.open-meteo.com/v1/search")
                .queryParam("name", city)
                .queryParam("count", 1)
                .queryParam("language", "zh")
                .queryParam("format", "json")
                .build()
                .encode()
                .toUri();

        Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
        if (body == null || !body.containsKey("results")) {
            return null;
        }
        Object resultsObj = body.get("results");
        if (!(resultsObj instanceof List<?> results) || results.isEmpty()) {
            return null;
        }
        Object first = results.get(0);
        if (!(first instanceof Map<?, ?> item)) {
            return null;
        }
        String name = Objects.toString(item.get("name"), city);
        Double latitude = toDouble(item.get("latitude"));
        Double longitude = toDouble(item.get("longitude"));
        if (latitude == null || longitude == null) {
            return null;
        }
        return new GeoResult(name, latitude, longitude);
    }

    private LocalDate parseDate(String date) {
        String normalized = normalizeParam(date);
        if (normalized == null || normalized.isBlank()) {
            return LocalDate.now();
        }
        return LocalDate.parse(normalized);
    }

    private String normalizeParam(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        if (result.length() >= 2) {
            boolean wrappedByDoubleQuote = result.startsWith("\"") && result.endsWith("\"");
            boolean wrappedBySingleQuote = result.startsWith("'") && result.endsWith("'");
            boolean wrappedByChineseQuote = result.startsWith("“") && result.endsWith("”");
            if (wrappedByDoubleQuote || wrappedBySingleQuote || wrappedByChineseQuote) {
                result = result.substring(1, result.length() - 1).trim();
            }
        }
        return result;
    }

    private List<Double> firstN(Object value, int n) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Double> values = new java.util.ArrayList<>();
        int limit = Math.min(Math.max(1, n), list.size());
        for (int i = 0; i < limit; i++) {
            Double v = toDouble(list.get(i));
            if (v != null) {
                values.add(v);
            }
        }
        return values;
    }

    private Double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new java.util.ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2D;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String formatNumber(Double value) {
        if (value == null) {
            return "unknown";
        }
        return String.format("%.1f", value);
    }

    private String airLevel(Double aqi) {
        if (aqi == null) {
            return "未知";
        }
        if (aqi <= 50) {
            return "优";
        }
        if (aqi <= 100) {
            return "良";
        }
        if (aqi <= 150) {
            return "轻度污染";
        }
        if (aqi <= 200) {
            return "中度污染";
        }
        if (aqi <= 300) {
            return "重度污染";
        }
        return "严重污染";
    }

    private String airAdvice(Double aqi, Double pm25) {
        if (aqi == null && pm25 == null) {
            return "空气数据不足，建议关注实时空气质量。";
        }
        double score = aqi != null ? aqi : Math.max(0D, pm25 == null ? 0D : pm25 * 2D);
        if (score <= 100) {
            return "可正常出行，馆内外步行影响较小。";
        }
        if (score <= 150) {
            return "建议缩短室外步行，佩戴口罩。";
        }
        return "建议优先室内路线，减少户外停留并佩戴防护口罩。";
    }

    private record GeoResult(String name, double latitude, double longitude) {
    }
}

