package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.ImageRetrieveHitVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageRetrieveResponse {
    private ImageRetrieveHitVO[] hits;
}

