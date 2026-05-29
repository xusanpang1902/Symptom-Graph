package com.symptomgraph.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GeminiRecognitionResult {

    private String platform;

    @JsonProperty("context_target")
    private String contextTarget;

    @JsonProperty("original_publish_time")
    private String originalPublishTime;

    private List<GeminiRecognitionItem> items = new ArrayList<>();

    @JsonIgnore
    private String modelRawResponse;
}
