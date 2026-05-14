package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.MuseumMapPlanRequest;
import com.kama.jchatmind.model.response.MuseumMapPlanResponse;

public interface MuseumMapService {
    MuseumMapPlanResponse searchMuseumsAndPlan(MuseumMapPlanRequest request);
}

