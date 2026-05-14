package com.kama.jchatmind.agent.tools.test;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class WeatherTool implements Tool {

    private final RestClient restClient;

    public WeatherTool(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public String getName() {
        return "weatherTool";
    }

    @Override
    public String getDescription() {
        return "根据城市查询实时天气或指定日期天气（真实接口）";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "weather",
            description = "查询城市天气。支持中文或英文城市名；若用户未明确城市，可先调用 getCity 获取当前位置。date 使用 yyyy-MM-dd 格式，可为空。"
    )
    public String getWeather(String city, String date) {
        String normalizedCity = normalizeParam(city);
        if (normalizedCity == null || normalizedCity.isBlank()) {
            return "错误：city 不能为空";
        }

        try {
            GeoResult geo = geocode(normalizedCity);
            if (geo == null) {
                return "未找到城市：" + normalizedCity;
            }

            LocalDate targetDate = parseDate(date);
            LocalDate today = LocalDate.now();

            if (targetDate.equals(today)) {
                return queryCurrentWeather(geo, targetDate);
            }
            return queryDailyWeather(geo, targetDate);
        } catch (Exception e) {
            return "查询天气失败: " + e.getMessage();
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

    private String queryCurrentWeather(GeoResult geo, LocalDate date) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://api.open-meteo.com/v1/forecast")
                .queryParam("latitude", geo.latitude())
                .queryParam("longitude", geo.longitude())
                .queryParam("current", "temperature_2m,relative_humidity_2m,weather_code")
                .queryParam("timezone", "Asia/Shanghai")
                .build(true)
                .toUri();

        Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
        if (body == null || !(body.get("current") instanceof Map<?, ?> current)) {
            return geo.name() + " " + date + " 暂无天气数据";
        }

        Double temp = toDouble(current.get("temperature_2m"));
        Double humidity = toDouble(current.get("relative_humidity_2m"));
        Integer code = toInt(current.get("weather_code"));

        return String.format(
                "%s %s 天气：%s，温度 %.1f°C，湿度 %.0f%%",
                geo.name(),
                date,
                weatherCodeToText(code),
                temp == null ? 0.0 : temp,
                humidity == null ? 0.0 : humidity
        );
    }

    private String queryDailyWeather(GeoResult geo, LocalDate date) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://api.open-meteo.com/v1/forecast")
                .queryParam("latitude", geo.latitude())
                .queryParam("longitude", geo.longitude())
                .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min")
                .queryParam("timezone", "Asia/Shanghai")
                .queryParam("start_date", date)
                .queryParam("end_date", date)
                .build(true)
                .toUri();

        Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
        if (body == null || !(body.get("daily") instanceof Map<?, ?> daily)) {
            return geo.name() + " " + date + " 暂无天气数据";
        }

        Integer code = firstInt(daily.get("weather_code"));
        Double max = firstDouble(daily.get("temperature_2m_max"));
        Double min = firstDouble(daily.get("temperature_2m_min"));

        return String.format(
                "%s %s 天气：%s，最高 %.1f°C，最低 %.1f°C",
                geo.name(),
                date,
                weatherCodeToText(code),
                max == null ? 0.0 : max,
                min == null ? 0.0 : min
        );
    }

    private LocalDate parseDate(String date) {
        String normalizedDate = normalizeParam(date);
        if (normalizedDate == null || normalizedDate.isBlank()) {
            return LocalDate.now();
        }
        return LocalDate.parse(normalizedDate);
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

    private Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer firstInt(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return toInt(list.get(0));
        }
        return null;
    }

    private Double firstDouble(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return toDouble(list.get(0));
        }
        return null;
    }

    private String weatherCodeToText(Integer code) {
        if (code == null) {
            return "未知";
        }
        return switch (code) {
            case 0 -> "晴";
            case 1, 2, 3 -> "多云";
            case 45, 48 -> "雾";
            case 51, 53, 55, 56, 57 -> "毛毛雨";
            case 61, 63, 65, 66, 67 -> "雨";
            case 71, 73, 75, 77 -> "雪";
            case 80, 81, 82 -> "阵雨";
            case 95, 96, 99 -> "雷暴";
            default -> "未知";
        };
    }

    private record GeoResult(String name, double latitude, double longitude) {
    }
}
