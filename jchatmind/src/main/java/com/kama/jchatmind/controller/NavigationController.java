package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.NavigationPlanRequest;
import com.kama.jchatmind.model.response.NavigationPlanResponse;
import com.kama.jchatmind.service.NavigationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NavigationController {

    private final NavigationService navigationService;

    @PostMapping({"/api/navigation/plan", "/navigation/plan"})
    public ApiResponse<NavigationPlanResponse> plan(@RequestBody NavigationPlanRequest request) {
        return ApiResponse.success(navigationService.planRoute(request));
    }
}
