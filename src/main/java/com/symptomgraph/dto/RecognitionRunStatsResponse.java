package com.symptomgraph.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class RecognitionRunStatsResponse {

    private long totalRuns;

    private List<RecognitionRunStatsItem> items = new ArrayList<>();

    @Data
    public static class RecognitionRunStatsItem {

        private String provider;

        private String model;

        private long totalRuns;

        private long successRuns;

        private long emptyRuns;

        private long processingRuns;

        private long failedRuns;

        private double successRate;

        private double emptyRate;

        private double failureRate;

        private Long averageDurationMs;

        private Long inputTokens;

        private Long outputTokens;

        private Long totalTokens;

        private BigDecimal estimatedCost;
    }
}
