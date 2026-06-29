package com.symptomgraph.controller;

import com.symptomgraph.dto.RecognitionRunStatsResponse;
import com.symptomgraph.entity.RecognitionRun;
import com.symptomgraph.service.RecognitionRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recognition-runs")
public class RecognitionRunController {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_EMPTY_RESULT = "EMPTY_RESULT";
    private static final String STATUS_PROCESSING = "PROCESSING";

    private final RecognitionRunService recognitionRunService;

    public RecognitionRunController(RecognitionRunService recognitionRunService) {
        this.recognitionRunService = recognitionRunService;
    }

    @GetMapping("/stats")
    public RecognitionRunStatsResponse stats() {
        List<RecognitionRun> runs = recognitionRunService.list();
        Map<GroupKey, MutableStats> groupedStats = new LinkedHashMap<>();
        for (RecognitionRun run : runs) {
            GroupKey key = new GroupKey(run.getProvider(), run.getModel());
            groupedStats.computeIfAbsent(key, ignored -> new MutableStats()).add(run);
        }

        RecognitionRunStatsResponse response = new RecognitionRunStatsResponse();
        response.setTotalRuns(runs.size());
        response.setItems(groupedStats.entrySet().stream()
                .map(entry -> toItem(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(RecognitionRunStatsResponse.RecognitionRunStatsItem::getTotalRuns).reversed())
                .toList());
        return response;
    }

    private RecognitionRunStatsResponse.RecognitionRunStatsItem toItem(GroupKey key, MutableStats stats) {
        RecognitionRunStatsResponse.RecognitionRunStatsItem item = new RecognitionRunStatsResponse.RecognitionRunStatsItem();
        item.setProvider(key.provider());
        item.setModel(key.model());
        item.setTotalRuns(stats.totalRuns);
        item.setSuccessRuns(stats.successRuns);
        item.setEmptyRuns(stats.emptyRuns);
        item.setProcessingRuns(stats.processingRuns);
        item.setFailedRuns(stats.failedRuns);
        item.setSuccessRate(rate(stats.successRuns, stats.totalRuns));
        item.setEmptyRate(rate(stats.emptyRuns, stats.totalRuns));
        item.setFailureRate(rate(stats.failedRuns, stats.totalRuns));
        item.setAverageDurationMs(stats.durationCount == 0 ? null : stats.durationTotal / stats.durationCount);
        item.setInputTokens(stats.inputTokens);
        item.setOutputTokens(stats.outputTokens);
        item.setTotalTokens(stats.totalTokens);
        item.setEstimatedCost(stats.estimatedCost);
        return item;
    }

    private double rate(long value, long total) {
        if (total == 0) {
            return 0;
        }
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private record GroupKey(String provider, String model) {
    }

    private static class MutableStats {

        private long totalRuns;

        private long successRuns;

        private long emptyRuns;

        private long processingRuns;

        private long failedRuns;

        private long durationTotal;

        private long durationCount;

        private long totalTokens;

        private long inputTokens;

        private long outputTokens;

        private BigDecimal estimatedCost = BigDecimal.ZERO;

        private void add(RecognitionRun run) {
            totalRuns++;
            if (STATUS_SUCCESS.equals(run.getStatus())) {
                successRuns++;
            } else if (STATUS_EMPTY_RESULT.equals(run.getStatus())) {
                emptyRuns++;
            } else if (STATUS_PROCESSING.equals(run.getStatus())) {
                processingRuns++;
            } else {
                failedRuns++;
            }
            if (run.getDurationMs() != null) {
                durationTotal += run.getDurationMs();
                durationCount++;
            }
            if (run.getInputTokens() != null) {
                inputTokens += run.getInputTokens();
            }
            if (run.getOutputTokens() != null) {
                outputTokens += run.getOutputTokens();
            }
            if (run.getTotalTokens() != null) {
                totalTokens += run.getTotalTokens();
            }
            if (run.getEstimatedCost() != null) {
                estimatedCost = estimatedCost.add(run.getEstimatedCost());
            }
        }
    }
}
