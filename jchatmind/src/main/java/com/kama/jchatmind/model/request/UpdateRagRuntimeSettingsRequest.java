package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class UpdateRagRuntimeSettingsRequest {
    private String mode;
    private Boolean rerankEnabled;
}

