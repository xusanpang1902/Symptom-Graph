package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.oss")
public class OssProperties {

    private String endpoint;

    private String bucket;

    private String accessKeyId;

    private String accessKeySecret;

    private String objectPrefix = "corpus/";

    private long signedUrlExpirationMinutes = 30;
}
