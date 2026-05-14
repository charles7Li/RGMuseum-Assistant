package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.ChunkBgeM3;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(String kbId, String title);
    List<String> similaritySearch(String kbId, String title, String artifactId);

    List<ChunkBgeM3> similaritySearchChunks(String kbId, String query, int limit);
    List<ChunkBgeM3> similaritySearchChunks(String kbId, String query, int limit, String artifactId);
}
