package com.symptomgraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.feishu")
public class FeishuProperties {

    private boolean enabled = false;

    private String appId;

    private String appSecret;

    private String verificationToken;

    private String encryptKey;

    private String baseUrl = "https://open.feishu.cn/open-apis";

    private String manageBaseUrl;
}
