package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MuseumRouteCandidateVO {
    private String poiId;
    private String museumName;
    private String address;
    private Double longitude;
    private Double latitude;
    private String openTime;
    private String closingTime;
    private String businessStatus;
    private Double distanceMeters;
    private Double durationSeconds;
    private String overview;
    private String polyline;
    private NavigationRouteStepVO[] steps;
    private String routeError;
}

