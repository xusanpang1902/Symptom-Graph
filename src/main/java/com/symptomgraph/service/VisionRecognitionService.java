package com.symptomgraph.service;

import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.dto.VisionRecognitionOptions;

public interface VisionRecognitionService {

    VisionRecognitionResult recognize(byte[] imageBytes, String mimeType);

    default VisionRecognitionResult recognize(byte[] imageBytes, String mimeType, VisionRecognitionOptions options) {
        return recognize(imageBytes, mimeType);
    }
}
