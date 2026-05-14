package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NavigationRouteStepVO {
    private Integer index;
    private String instruction;
    private String road;
    private Double distanceMeters;
    private Double durationSeconds;
    private String polyline;
}
