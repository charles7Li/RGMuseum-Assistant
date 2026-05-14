package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.NavigationPlanRequest;
import com.kama.jchatmind.model.response.NavigationPlanResponse;

public interface NavigationService {
    NavigationPlanResponse planRoute(NavigationPlanRequest request);
}
