package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.exception.VisionRecognitionException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VisionRecognitionJsonParser {

    public static final String STATUS_PARSE_FAILED = "PARSE_FAILED";

    private final ObjectMapper objectMapper;

    public VisionRecognitionJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VisionRecognitionResult parse(String modelContentText) {
        String recognitionJson = cleanJsonText(modelContentText);
        try {
            VisionRecognitionResult recognitionResult = objectMapper.readValue(recognitionJson, VisionRecognitionResult.class);
            if (recognitionResult.getItems() == null) {
                recognitionResult.setItems(List.of());
            }
            return recognitionResult;
        } catch (JsonProcessingException ex) {
            throw new VisionRecognitionException(STATUS_PARSE_FAILED, "Vision recognition result is not valid JSON", modelContentText, ex);
        }
    }

    String cleanJsonText(String modelContentText) {
        if (modelContentText == null) {
            return "";
        }

        String cleanedJsonText = modelContentText.trim();
        if (cleanedJsonText.startsWith("```")) {
            cleanedJsonText = cleanedJsonText.replaceFirst("^```(?:json|JSON)?\\s*", "");
            cleanedJsonText = cleanedJsonText.replaceFirst("\\s*```$", "");
        }
        return cleanedJsonText.trim();
    }
}
