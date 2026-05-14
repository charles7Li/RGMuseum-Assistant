package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.RagRetrieveHitVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagRetrieveResponse {
    private RagRetrieveHitVO[] hits;
}

