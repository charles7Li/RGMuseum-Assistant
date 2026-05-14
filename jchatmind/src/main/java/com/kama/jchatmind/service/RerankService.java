package com.kama.jchatmind.service;

import java.util.List;

public interface RerankService {
    List<Double> rerank(String query, List<String> documents);
}

