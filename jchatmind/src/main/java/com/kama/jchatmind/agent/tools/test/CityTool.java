package com.kama.jchatmind.agent.tools.test;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Objects;

@Component
public class CityTool implements Tool {

    private final RestClient restClient;

    @Value("${weather.default-city:广州}")
    private String defaultCity;

    public CityTool(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public String getName() {
        return "cityTool";
    }

    @Override
    public String getDescription() {
        return "获取当前城市（优先 IP 定位，失败时回退默认城市）";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "getCity",
            description = "获取当前城市。优先通过 IP 定位城市，失败时返回默认城市。用户问“我这里天气如何”时可先调用此工具，再调用 weather。"
    )
    public String getCity() {
        String cityFromIp = locateCityByIp();
        if (cityFromIp != null && !cityFromIp.isBlank()) {
            return cityFromIp;
        }
        return defaultCity;
    }

    private String locateCityByIp() {
        try {
            Map<?, ?> body = restClient.get()
                    .uri("https://ipwho.is/")
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                return null;
            }

            Object successValue = body.get("success");
            if (successValue instanceof Boolean success && !success) {
                return null;
            }

            String city = toText(body.get("city"));
            if (city != null && !city.isBlank()) {
                return city;
            }

            String region = toText(body.get("region"));
            if (region != null && !region.isBlank()) {
                return region;
            }

            return toText(body.get("country"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toText(Object value) {
        if (value == null) {
            return null;
        }
        return Objects.toString(value, null);
    }
}
