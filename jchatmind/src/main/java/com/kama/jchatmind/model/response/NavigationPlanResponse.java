package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.NavigationRouteStepVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NavigationPlanResponse {
    private String provider;
    private String mode;
    private String origin;
    private String destination;
    private Double distanceMeters;
    private Double durationSeconds;
    private String overview;
    private String polyline;
    private NavigationRouteStepVO[] steps;
}
