package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@Slf4j
public class DocumentStorageServiceImpl implements DocumentStorageService {

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    @Override
    public String saveFile(String kbId, String documentId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("uploaded file is empty");
        }
        Path targetPath = buildTargetPath(kbId, documentId, file.getOriginalFilename());
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        String relativePath = buildRelativePath(kbId, documentId, targetPath.getFileName().toString());
        log.info("file saved: kbId={}, documentId={}, filename={}, path={}",
                kbId, documentId, file.getOriginalFilename(), relativePath);
        return relativePath;
    }

    @Override
    public String saveBytes(String kbId, String documentId, String originalFilename, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("uploaded bytes are empty");
        }
        Path targetPath = buildTargetPath(kbId, documentId, originalFilename);
        Files.write(targetPath, data);
        String relativePath = buildRelativePath(kbId, documentId, targetPath.getFileName().toString());
        log.info("bytes saved: kbId={}, documentId={}, filename={}, path={}",
                kbId, documentId, originalFilename, relativePath);
        return relativePath;
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path fullPath = getFilePath(filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.info("file deleted: {}", filePath);

            Path parentDir = fullPath.getParent();
            if (parentDir != null && Files.exists(parentDir)) {
                try {
                    Files.delete(parentDir);
                    log.info("directory deleted: {}", parentDir);
                } catch (IOException e) {
                    log.debug("skip deleting non-empty directory: {}", parentDir);
                }
            }
        } else {
            log.warn("file not found, skip deleting: {}", filePath);
        }
    }

    @Override
    public Path getFilePath(String filePath) {
        return Paths.get(baseStoragePath, filePath);
    }

    @Override
    public boolean fileExists(String filePath) {
        Path fullPath = getFilePath(filePath);
        return Files.exists(fullPath) && Files.isRegularFile(fullPath);
    }

    private Path buildTargetPath(String kbId, String documentId, String originalFilename) throws IOException {
        Path documentDir = Paths.get(baseStoragePath, kbId, documentId);
        Files.createDirectories(documentDir);
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID() + extension;
        return documentDir.resolve(uniqueFilename);
    }

    private String buildRelativePath(String kbId, String documentId, String filename) {
        return Paths.get(kbId, documentId, filename).toString().replace("\\", "/");
    }
}
