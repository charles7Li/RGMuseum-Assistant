package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kama.jchatmind.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;

@Service
@ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "http")
@Slf4j
public class HttpRerankServiceImpl implements RerankService {

    private final WebClient webClient;
    private final String rerankPath;
    private final String rerankModel;

    public HttpRerankServiceImpl(
            WebClient.Builder builder,
            @Value("${rag.rerank.http.base-url:http://localhost:18000}") String rerankBaseUrl,
            @Value("${rag.rerank.http.path:/rerank}") String rerankPath,
            @Value("${rag.rerank.model:}") String rerankModel
    ) {
        // Uvicorn/FastAPI may intermittently reject upgrade attempts with 400.
        // Force HTTP/1.1 to keep rerank calls stable.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.webClient = builder
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .baseUrl(rerankBaseUrl)
                .build();
        this.rerankPath = rerankPath;
        this.rerankModel = rerankModel;
    }

    @Override
    public List<Double> rerank(String query, List<String> documents) {
        Assert.hasText(query, "query cannot be empty");
        Assert.notNull(documents, "documents cannot be null");
        if (documents.isEmpty()) {
            return List.of();
        }

        Map<String, Object> request = new HashMap<>();
        request.put("query", query);
        request.put("documents", documents);
        request.put("top_n", documents.size());
        if (StringUtils.hasText(rerankModel)) {
            request.put("model", rerankModel);
        }

        JsonNode response = webClient.post()
                .uri(rerankPath)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<Double> scores = parseScores(response, documents.size());
        log.debug("HTTP rerank finished: path={}, docs={}", rerankPath, documents.size());
        return scores;
    }

    private List<Double> parseScores(JsonNode response, int docCount) {
        if (response == null) {
            throw new IllegalStateException("Empty rerank response");
        }
        if (response.has("scores") && response.get("scores").isArray()) {
            return parseDenseScoreArray(response.get("scores"), docCount);
        }
        if (response.has("results") && response.get("results").isArray()) {
            return parseIndexedResults(response.get("results"), docCount);
        }
        if (response.has("data") && response.get("data").isArray()) {
            return parseIndexedResults(response.get("data"), docCount);
        }
        if (response.isArray()) {
            return parseIndexedResults(response, docCount);
        }
        throw new IllegalStateException("Unsupported rerank response format");
    }

    private List<Double> parseDenseScoreArray(JsonNode scoreArray, int docCount) {
        List<Double> scores = initScores(docCount);
        int n = Math.min(scoreArray.size(), docCount);
        for (int i = 0; i < n; i++) {
            scores.set(i, scoreArray.get(i).asDouble(0D));
        }
        return scores;
    }

    private List<Double> parseIndexedResults(JsonNode items, int docCount) {
        List<Double> scores = initScores(docCount);
        for (JsonNode item : items) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= docCount) {
                continue;
            }
            double score;
            if (item.has("relevance_score")) {
                score = item.path("relevance_score").asDouble(0D);
            } else {
                score = item.path("score").asDouble(0D);
            }
            scores.set(index, score);
        }
        return scores;
    }

    private List<Double> initScores(int docCount) {
        List<Double> scores = new ArrayList<>(docCount);
        for (int i = 0; i < docCount; i++) {
            scores.add(0D);
        }
        return scores;
    }
}
