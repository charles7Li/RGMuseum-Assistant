package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.request.MuseumMapPlanRequest;
import com.kama.jchatmind.model.response.MuseumMapPlanResponse;
import com.kama.jchatmind.model.vo.MuseumRouteCandidateVO;
import com.kama.jchatmind.service.MuseumMapService;
import org.springframework.stereotype.Component;

@Component
public class MuseumMapRouteTool implements Tool {

    private final MuseumMapService museumMapService;

    public MuseumMapRouteTool(MuseumMapService museumMapService) {
        this.museumMapService = museumMapService;
    }

    @Override
    public String getName() {
        return "museumMapRouteTool";
    }

    @Override
    public String getDescription() {
        return "根据目标地点搜索博物馆，返回营业/闭馆时间并自动规划路线。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "museumMapRoute",
            description = "地图查询博物馆并规划路线。参数：target(必填)、originLongitude/originLatitude(必填)、city(可选)、mode(可选walking|driving)、poiLimit(可选)、routeLimit(可选)。输出每个博物馆的营业/闭馆时间与路线摘要。"
    )
    public String museumMapRoute(
            String target,
            Double originLongitude,
            Double originLatitude,
            String city,
            String mode,
            Integer poiLimit,
            Integer routeLimit
    ) {
        MuseumMapPlanRequest request = new MuseumMapPlanRequest();
        request.setTarget(target);
        request.setOriginLongitude(originLongitude);
        request.setOriginLatitude(originLatitude);
        request.setCity(city);
        request.setMode(mode);
        request.setPoiLimit(poiLimit);
        request.setRouteLimit(routeLimit);
        request.setCityLimit(true);

        MuseumMapPlanResponse response = museumMapService.searchMuseumsAndPlan(request);
        StringBuilder out = new StringBuilder();
        out.append("status=ok\n");
        out.append("provider=").append(response.getProvider()).append("\n");
        out.append("target=").append(response.getTarget()).append("\n");
        out.append("city=").append(response.getCity() == null ? "" : response.getCity()).append("\n");
        out.append("mode=").append(response.getMode()).append("\n");
        out.append("summary=").append(response.getSummary()).append("\n");
        out.append("candidates=");

        MuseumRouteCandidateVO[] candidates = response.getCandidates();
        if (candidates == null || candidates.length == 0) {
            out.append("none\n");
            return out.toString().trim();
        }
        out.append("\n");
        for (int i = 0; i < candidates.length; i++) {
            MuseumRouteCandidateVO candidate = candidates[i];
            out.append(i + 1).append(". ")
                    .append(candidate.getMuseumName())
                    .append(" | close=")
                    .append(candidate.getClosingTime())
                    .append(" | open=")
                    .append(candidate.getOpenTime())
                    .append(" | distance=")
                    .append(candidate.getDistanceMeters() == null ? "unknown" : candidate.getDistanceMeters())
                    .append(" | duration=")
                    .append(candidate.getDurationSeconds() == null ? "unknown" : candidate.getDurationSeconds())
                    .append("\n");
        }
        out.append("answerPolicy=优先给出闭馆时间更早且路线更短的候选。");
        return out.toString().trim();
    }
}

