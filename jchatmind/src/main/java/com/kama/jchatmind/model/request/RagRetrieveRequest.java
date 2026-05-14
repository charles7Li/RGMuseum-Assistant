package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class RagRetrieveRequest {
    private String kbId;
    private String query;
    private Integer topK;
}

