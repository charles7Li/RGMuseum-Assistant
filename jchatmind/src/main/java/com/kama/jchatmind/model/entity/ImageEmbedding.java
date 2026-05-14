package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageEmbedding {
    private String id;
    private String kbId;
    private String docId;
    private String fileName;
    private String filePath;
    private String metadata;
    private float[] embedding;
    private Double distance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        ImageEmbedding other = (ImageEmbedding) that;
        return (id == null ? other.id == null : id.equals(other.id))
                && (kbId == null ? other.kbId == null : kbId.equals(other.kbId))
                && (docId == null ? other.docId == null : docId.equals(other.docId))
                && (fileName == null ? other.fileName == null : fileName.equals(other.fileName))
                && (filePath == null ? other.filePath == null : filePath.equals(other.filePath))
                && (metadata == null ? other.metadata == null : metadata.equals(other.metadata))
                && Arrays.equals(embedding, other.embedding)
                && (createdAt == null ? other.createdAt == null : createdAt.equals(other.createdAt))
                && (updatedAt == null ? other.updatedAt == null : updatedAt.equals(other.updatedAt));
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + (id == null ? 0 : id.hashCode());
        result = 31 * result + (kbId == null ? 0 : kbId.hashCode());
        result = 31 * result + (docId == null ? 0 : docId.hashCode());
        result = 31 * result + (fileName == null ? 0 : fileName.hashCode());
        result = 31 * result + (filePath == null ? 0 : filePath.hashCode());
        result = 31 * result + (metadata == null ? 0 : metadata.hashCode());
        result = 31 * result + Arrays.hashCode(embedding);
        result = 31 * result + (createdAt == null ? 0 : createdAt.hashCode());
        result = 31 * result + (updatedAt == null ? 0 : updatedAt.hashCode());
        return result;
    }
}
