package com.symptomgraph.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OssUploadResult {

    private String bucket;

    private String objectKey;

    private String originalFilename;

    private String contentType;

    private long size;
}
