package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.UpdateRagRuntimeSettingsRequest;
import com.kama.jchatmind.model.response.RagRuntimeSettingsResponse;
import com.kama.jchatmind.service.RagRuntimeSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RagRuntimeSettingsController {

    private final RagRuntimeSettingsService ragRuntimeSettingsService;

    @GetMapping({"/api/rag/runtime-settings", "/rag/runtime-settings"})
    public ApiResponse<RagRuntimeSettingsResponse> getRuntimeSettings() {
        return ApiResponse.success(RagRuntimeSettingsResponse.builder()
                .mode(ragRuntimeSettingsService.getMode())
                .rerankEnabled(ragRuntimeSettingsService.isRerankEnabled())
                .build());
    }

    @PutMapping({"/api/rag/runtime-settings", "/rag/runtime-settings"})
    public ApiResponse<RagRuntimeSettingsResponse> updateRuntimeSettings(
            @RequestBody(required = false) UpdateRagRuntimeSettingsRequest request
    ) {
        UpdateRagRuntimeSettingsRequest safeRequest = request == null ? new UpdateRagRuntimeSettingsRequest() : request;
        ragRuntimeSettingsService.update(safeRequest.getMode(), safeRequest.getRerankEnabled());
        return ApiResponse.success(RagRuntimeSettingsResponse.builder()
                .mode(ragRuntimeSettingsService.getMode())
                .rerankEnabled(ragRuntimeSettingsService.isRerankEnabled())
                .build());
    }
}

