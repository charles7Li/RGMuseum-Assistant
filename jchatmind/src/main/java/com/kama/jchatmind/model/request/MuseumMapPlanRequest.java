package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class MuseumMapPlanRequest {
    private Double originLongitude;
    private Double originLatitude;
    private String target;
    private String city;
    private String mode;
    private Integer poiLimit;
    private Integer routeLimit;
    private Boolean cityLimit;
}

