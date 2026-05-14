package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.ImageEmbedding;
import com.kama.jchatmind.model.response.ImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ImageRagService {
    ImageUploadResponse uploadAndIndexImage(String kbId, MultipartFile file);
    ImageUploadResponse uploadAndIndexImageBytes(String kbId, String originalFilename, String contentType, byte[] bytes, String sourceDocumentId);

    List<ImageEmbedding> retrieveByText(String kbId, String query, int topK);
}
