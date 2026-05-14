package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.MuseumMapPlanRequest;
import com.kama.jchatmind.model.response.MuseumMapPlanResponse;
import com.kama.jchatmind.service.MuseumMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MuseumMapController {

    private final MuseumMapService museumMapService;

    @PostMapping({"/api/museum/map/plan", "/museum/map/plan"})
    public ApiResponse<MuseumMapPlanResponse> plan(@RequestBody MuseumMapPlanRequest request) {
        return ApiResponse.success(museumMapService.searchMuseumsAndPlan(request));
    }
}

