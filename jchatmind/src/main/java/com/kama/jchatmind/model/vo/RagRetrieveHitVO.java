package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagRetrieveHitVO {
    private String chunkId;
    private String docId;
    private String source;
    private String content;
}

