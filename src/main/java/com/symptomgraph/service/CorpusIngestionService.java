package com.symptomgraph.service;

import com.symptomgraph.dto.CorpusUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface CorpusIngestionService {

    CorpusUploadResponse ingest(MultipartFile file, boolean force);

    default CorpusUploadResponse ingest(MultipartFile file, boolean force, String provider, String model) {
        return ingest(file, force);
    }
}
