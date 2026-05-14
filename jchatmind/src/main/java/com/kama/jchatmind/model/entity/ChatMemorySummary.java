package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatMemorySummary {
    private String id;
    private String sessionId;
    private String summaryText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
