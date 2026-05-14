package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.MuseumRouteCandidateVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MuseumMapPlanResponse {
    private String provider;
    private String target;
    private String city;
    private String mode;
    private String searchKeywords;
    private Integer candidateCount;
    private Integer plannedCount;
    private MuseumRouteCandidateVO[] candidates;
    private String summary;
}

