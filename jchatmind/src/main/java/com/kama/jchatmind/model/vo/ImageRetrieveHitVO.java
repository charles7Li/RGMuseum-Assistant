package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageRetrieveHitVO {
    private String imageId;
    private String docId;
    private String fileName;
    private String filePath;
    private String imageUrl;
}
