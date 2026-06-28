package com.symptomgraph.dto;

import lombok.Data;

@Data
public class VisionRecognitionOptions {

    private String provider;

    private String model;

    public static VisionRecognitionOptions of(String provider, String model) {
        VisionRecognitionOptions options = new VisionRecognitionOptions();
        options.setProvider(provider);
        options.setModel(model);
        return options;
    }
}
