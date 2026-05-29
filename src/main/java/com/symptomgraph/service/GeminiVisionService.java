package com.symptomgraph.service;

import com.symptomgraph.dto.GeminiRecognitionResult;

public interface GeminiVisionService {

    GeminiRecognitionResult recognize(byte[] imageBytes, String mimeType);
}
