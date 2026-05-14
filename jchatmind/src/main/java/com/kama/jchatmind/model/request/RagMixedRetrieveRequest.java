package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class RagMixedRetrieveRequest {
    private String kbId;
    private String query;
    private Integer textTopK;
    private Integer imageTopK;
}
