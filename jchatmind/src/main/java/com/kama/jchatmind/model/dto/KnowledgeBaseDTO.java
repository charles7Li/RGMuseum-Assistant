package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseDTO {
    public static final String EMBEDDING_RULE_TITLE_ONLY = "title-only";
    public static final String EMBEDDING_RULE_TITLE_CONTENT_500 = "title+content(500)";
    public static final String EMBEDDING_RULE_CONTENT_ONLY_500 = "content-only(500)";

    private String id;

    private String name;

    private String description;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
        private String version;
        private String embeddingRule;
    }

    @Override
    public String toString() {
        return "{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
