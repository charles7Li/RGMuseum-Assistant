package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.RerankService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "disabled")
public class DisabledRerankServiceImpl implements RerankService {

    @Override
    public List<Double> rerank(String query, List<String> documents) {
        List<Double> scores = new ArrayList<>();
        if (documents == null) {
            return scores;
        }
        for (int i = 0; i < documents.size(); i++) {
            scores.add(0D);
        }
        return scores;
    }
}

