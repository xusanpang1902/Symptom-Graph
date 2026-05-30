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

    public VisionRecognitionResult parse(String modelText) {
        String json = cleanJsonText(modelText);
        try {
            VisionRecognitionResult result = objectMapper.readValue(json, VisionRecognitionResult.class);
            if (result.getItems() == null) {
                result.setItems(List.of());
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new VisionRecognitionException(STATUS_PARSE_FAILED, "Vision recognition result is not valid JSON", modelText, ex);
        }
    }

    String cleanJsonText(String modelText) {
        if (modelText == null) {
            return "";
        }

        String text = modelText.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json|JSON)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }
}
