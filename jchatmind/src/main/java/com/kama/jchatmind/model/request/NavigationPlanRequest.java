package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class NavigationPlanRequest {
    private Double originLongitude;
    private Double originLatitude;
    private Double destinationLongitude;
    private Double destinationLatitude;
    private String destinationName;
    private String mode;
}
