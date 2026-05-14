package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.ImageRagService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {
    private static final int CHUNK_WINDOW_SIZE = 500;
    private static final int CHUNK_WINDOW_OVERLAP = 100;
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)");

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ImageRagService imageRagService;
    private final MilvusVectorSearchService milvusVectorSearchService;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                result.add(documentConverter.toVO(document));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder().documents(result.toArray(new DocumentVO[0])).build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                result.add(documentConverter.toVO(document));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder().documents(result.toArray(new DocumentVO[0])).build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(request);
            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("create document failed");
            }
            return CreateDocumentResponse.builder().documentId(document.getId()).build();
        } catch (JsonProcessingException e) {
            throw new BizException("create document serialization failed: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file, String embeddingRule) {
        try {
            if (file.isEmpty()) {
                throw new BizException("uploaded file is empty");
            }

            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("create document record failed");
            }

            String documentId = document.getId();
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);
            documentMapper.updateById(updatedDocument);

            String resolvedEmbeddingRule = resolveEmbeddingRule(kbId, embeddingRule);
            log.info("document uploaded: kbId={}, documentId={}, filename={}, embeddingRule={}",
                    kbId, documentId, originalFilename, resolvedEmbeddingRule);

            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                processMarkdownDocument(kbId, documentId, filePath, resolvedEmbeddingRule);
            } else {
                log.warn("unsupported upload file type for chunking: {}", filetype);
            }

            return CreateDocumentResponse.builder().documentId(documentId).build();
        } catch (IOException e) {
            log.error("save file failed", e);
            throw new BizException("upload document failed: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("document not found: " + documentId);
        }
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                documentStorageService.deleteFile(documentDTO.getMetadata().getFilePath());
            }
        } catch (Exception e) {
            log.warn("delete file failed but continue deleting db record, documentId={}, error={}",
                    documentId, e.getMessage());
        }
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("delete document failed");
        }
    }

    private void processMarkdownDocument(String kbId, String documentId, String filePath, String embeddingRule) {
        try {
            Path markdownPath = documentStorageService.getFilePath(filePath);
            processMarkdownTextChunks(kbId, documentId, markdownPath, embeddingRule);
            processMarkdownImages(kbId, documentId, markdownPath);
        } catch (Exception e) {
            log.error("process markdown failed, documentId={}", documentId, e);
        }
    }

    private void processMarkdownTextChunks(String kbId, String documentId, Path markdownPath, String embeddingRule) throws IOException {
        try (InputStream inputStream = Files.newInputStream(markdownPath)) {
            List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);
            if (sections.isEmpty()) {
                log.warn("no markdown sections extracted, documentId={}", documentId);
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            int chunkCount = 0;
            for (MarkdownParserService.MarkdownSection section : sections) {
                String title = section.getTitle();
                String content = section.getContent();
                if (title == null || title.trim().isEmpty()) {
                    continue;
                }
                String normalizedTitle = title.trim();
                String normalizedContent = content == null ? "" : content.trim();
                List<String> chunkContents = buildChunkContents(normalizedTitle, normalizedContent, embeddingRule);
                for (String chunkContent : chunkContents) {
                    String embeddingText = buildEmbeddingText(embeddingRule, normalizedTitle, chunkContent);
                    float[] embedding = ragService.embed(embeddingText);
                    ChunkBgeM3 chunk = ChunkBgeM3.builder()
                            .kbId(kbId)
                            .docId(documentId)
                            .content(chunkContent)
                            .metadata(null)
                            .embedding(embedding)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    int result = chunkBgeM3Mapper.insert(chunk);
                    if (result > 0) {
                        chunkCount++;
                        milvusVectorSearchService.insertBatch(List.of(chunk));
                    }
                }
            }
            log.info("markdown text chunks done, documentId={}, chunks={}", documentId, chunkCount);
        }
    }

    private void processMarkdownImages(String kbId, String documentId, Path markdownPath) {
        try {
            String markdown = Files.readString(markdownPath, StandardCharsets.UTF_8);
            List<String> imageTargets = extractMarkdownImageTargets(markdown);
            if (imageTargets.isEmpty()) {
                return;
            }
            int indexed = 0;
            for (String target : imageTargets) {
                try {
                    ImageSource source = resolveImageSource(markdownPath, target, indexed + 1);
                    if (source == null || source.bytes.length == 0) {
                        continue;
                    }
                    imageRagService.uploadAndIndexImageBytes(
                            kbId,
                            source.filename,
                            source.contentType,
                            source.bytes,
                            documentId
                    );
                    indexed++;
                } catch (Exception e) {
                    log.warn("skip markdown image target={}, reason={}", target, e.getMessage());
                }
            }
            log.info("markdown images indexed, documentId={}, total={}, indexed={}",
                    documentId, imageTargets.size(), indexed);
        } catch (Exception e) {
            log.warn("process markdown images failed, documentId={}, reason={}", documentId, e.getMessage());
        }
    }

    private List<String> extractMarkdownImageTargets(String markdownText) {
        if (markdownText == null || markdownText.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(markdownText);
        while (matcher.find()) {
            String raw = matcher.group(1);
            String normalized = normalizeMarkdownImageTarget(raw);
            if (normalized != null && !normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private String normalizeMarkdownImageTarget(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("<") && s.endsWith(">") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        int titleSep = s.indexOf(" \"");
        if (titleSep > 0) {
            s = s.substring(0, titleSep).trim();
        }
        return s;
    }

    private ImageSource resolveImageSource(Path markdownPath, String target, int index) throws IOException {
        if (target.startsWith("data:image/")) {
            return parseDataUri(target, index);
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return downloadHttpImage(target, index);
        }
        return loadLocalImage(markdownPath, target, index);
    }

    private ImageSource parseDataUri(String target, int index) {
        int comma = target.indexOf(',');
        if (comma < 0) {
            throw new BizException("invalid data uri");
        }
        String header = target.substring(0, comma);
        String body = target.substring(comma + 1);
        String contentType = "image/png";
        String ext = "png";
        int colon = header.indexOf(':');
        int semicolon = header.indexOf(';');
        if (colon >= 0 && semicolon > colon) {
            contentType = header.substring(colon + 1, semicolon);
            ext = contentType.startsWith("image/") ? contentType.substring("image/".length()) : "png";
        }
        byte[] bytes = Base64.getDecoder().decode(body);
        return new ImageSource("markdown_image_" + index + "." + ext, contentType, bytes);
    }

    private ImageSource downloadHttpImage(String target, int index) throws IOException {
        URL url = URI.create(target).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new BizException("http image download failed, status=" + code);
        }
        byte[] bytes;
        try (InputStream in = conn.getInputStream()) {
            bytes = readAllBytes(in);
        }
        String contentType = conn.getContentType();
        String ext = guessExtension(contentType, target);
        return new ImageSource("markdown_http_" + index + "." + ext, contentType, bytes);
    }

    private ImageSource loadLocalImage(Path markdownPath, String target, int index) throws IOException {
        Path baseDir = markdownPath.getParent();
        Path candidate;
        if (Paths.get(target).isAbsolute()) {
            candidate = Paths.get(target).normalize();
        } else {
            candidate = baseDir.resolve(target).normalize();
            if (!candidate.startsWith(baseDir.normalize())) {
                throw new BizException("invalid relative image path");
            }
        }
        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            throw new BizException("local image not found");
        }
        byte[] bytes = Files.readAllBytes(candidate);
        String contentType = Files.probeContentType(candidate);
        String filename = candidate.getFileName() == null ? ("markdown_local_" + index + ".png") : candidate.getFileName().toString();
        return new ImageSource(filename, contentType, bytes);
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private String guessExtension(String contentType, String urlLike) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType.substring("image/".length());
        }
        String typeFromPath = getFileType(urlLike);
        return "unknown".equals(typeFromPath) ? "png" : typeFromPath;
    }

    private String resolveEmbeddingRule(String kbId, String requestEmbeddingRule) {
        if (requestEmbeddingRule != null && !requestEmbeddingRule.isBlank()) {
            return normalizeEmbeddingRule(requestEmbeddingRule);
        }
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if (knowledgeBase == null) {
            return KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_CONTENT_500;
        }
        try {
            KnowledgeBaseDTO dto = knowledgeBaseConverter.toDTO(knowledgeBase);
            if (dto.getMetadata() == null || dto.getMetadata().getEmbeddingRule() == null) {
                return KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_CONTENT_500;
            }
            return normalizeEmbeddingRule(dto.getMetadata().getEmbeddingRule());
        } catch (Exception e) {
            log.warn("read knowledge base embeddingRule failed, kbId={}, error={}", kbId, e.getMessage());
            return KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_CONTENT_500;
        }
    }

    private String normalizeEmbeddingRule(String embeddingRule) {
        if (embeddingRule == null || embeddingRule.isBlank()) {
            return KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_CONTENT_500;
        }
        return switch (embeddingRule.trim()) {
            case KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_ONLY -> KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_ONLY;
            case KnowledgeBaseDTO.EMBEDDING_RULE_CONTENT_ONLY_500 -> KnowledgeBaseDTO.EMBEDDING_RULE_CONTENT_ONLY_500;
            default -> KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_CONTENT_500;
        };
    }

    private String buildEmbeddingText(String embeddingRule, String title, String contentChunk) {
        if (contentChunk.isBlank()) {
            return title;
        }
        return switch (embeddingRule) {
            case KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_ONLY -> title;
            case KnowledgeBaseDTO.EMBEDDING_RULE_CONTENT_ONLY_500 -> contentChunk;
            default -> title + "\n" + contentChunk;
        };
    }

    private List<String> buildChunkContents(String title, String content, String embeddingRule) {
        String text = content.isBlank() ? title : content;
        if (KnowledgeBaseDTO.EMBEDDING_RULE_TITLE_ONLY.equals(embeddingRule)) {
            return List.of(text);
        }
        return splitByWindow(text, CHUNK_WINDOW_SIZE, CHUNK_WINDOW_OVERLAP);
    }

    private List<String> splitByWindow(String text, int windowSize, int overlap) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        if (windowSize <= 0 || normalized.length() <= windowSize) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + windowSize);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = end - overlap;
            if (nextStart <= start) {
                nextStart = start + 1;
            }
            start = nextStart;
        }
        return chunks;
    }

    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("document not found: " + documentId);
            }

            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);
            documentConverter.updateDTOFromRequest(documentDTO, request);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("update document failed");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("update document serialization failed: " + e.getMessage());
        }
    }

    private static final class ImageSource {
        private final String filename;
        private final String contentType;
        private final byte[] bytes;

        private ImageSource(String filename, String contentType, byte[] bytes) {
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = bytes;
        }
    }
}
