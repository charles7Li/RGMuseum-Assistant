package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.ImageRetrieveHitVO;
import com.kama.jchatmind.model.vo.RagRetrieveHitVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagMixedRetrieveResponse {
    private RagRetrieveHitVO[] textHits;
    private ImageRetrieveHitVO[] imageHits;
}
