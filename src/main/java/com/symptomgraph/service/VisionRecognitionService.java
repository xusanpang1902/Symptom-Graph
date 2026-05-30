package com.symptomgraph.service;

import com.symptomgraph.dto.VisionRecognitionResult;

public interface VisionRecognitionService {

    VisionRecognitionResult recognize(byte[] imageBytes, String mimeType);
}
