package com.symptomgraph.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class GeminiRecognitionItem {

    @JsonProperty("comment_index")
    private Integer commentIndex;

    @JsonProperty("raw_content")
    private String rawContent;

    private List<String> tags;
}
