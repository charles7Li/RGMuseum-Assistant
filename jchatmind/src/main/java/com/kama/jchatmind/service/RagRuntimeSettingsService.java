package com.kama.jchatmind.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RagRuntimeSettingsService {

    private final AtomicReference<String> mode = new AtomicReference<>("fast");
    private final AtomicBoolean rerankEnabled = new AtomicBoolean(true);

    public RagRuntimeSettingsService(
            @Value("${rag.runtime.mode:fast}") String initialMode,
            @Value("${rag.rerank.enabled:true}") boolean initialRerankEnabled
    ) {
        this.mode.set(normalizeMode(initialMode));
        this.rerankEnabled.set(initialRerankEnabled);
    }

    public String getMode() {
        return mode.get();
    }

    public boolean isFastMode() {
        return !"deep".equals(mode.get());
    }

    public boolean isRerankEnabled() {
        return rerankEnabled.get();
    }

    public void update(String newMode, Boolean newRerankEnabled) {
        if (newMode != null) {
            mode.set(normalizeMode(newMode));
        }
        if (newRerankEnabled != null) {
            rerankEnabled.set(newRerankEnabled);
        }
    }

    private String normalizeMode(String rawMode) {
        if (!StringUtils.hasText(rawMode)) {
            return "fast";
        }
        String normalized = rawMode.trim().toLowerCase(Locale.ROOT);
        if (!"fast".equals(normalized) && !"deep".equals(normalized)) {
            throw new IllegalArgumentException("mode must be 'fast' or 'deep'");
        }
        return normalized;
    }
}

