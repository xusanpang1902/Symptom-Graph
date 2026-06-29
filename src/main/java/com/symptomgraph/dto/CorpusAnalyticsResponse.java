package com.symptomgraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CorpusAnalyticsResponse {

    private long totalRecords;

    private long distinctCaptureCount;

    private List<CountItem> parseStatusCounts = new ArrayList<>();

    private List<CountItem> reviewStatusCounts = new ArrayList<>();

    private List<CountItem> platformCounts = new ArrayList<>();

    private List<CountItem> tagCounts = new ArrayList<>();

    private List<CountItem> dailyCounts = new ArrayList<>();

    @Data
    public static class CountItem {

        private String name;

        private long count;

        public static CountItem of(String name, long count) {
            CountItem item = new CountItem();
            item.setName(name);
            item.setCount(count);
            return item;
        }
    }
}
