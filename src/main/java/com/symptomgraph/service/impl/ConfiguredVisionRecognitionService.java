package com.symptomgraph.service.impl;

import com.symptomgraph.config.VisionProperties;
import com.symptomgraph.dto.VisionRecognitionOptions;
import com.symptomgraph.dto.VisionRecognitionResult;
import com.symptomgraph.exception.VisionRecognitionException;
import com.symptomgraph.service.VisionRecognitionProvider;
import com.symptomgraph.service.VisionRecognitionService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Primary
@Service
public class ConfiguredVisionRecognitionService implements VisionRecognitionService {

    public static final String STATUS_MODEL_FAILED = "MODEL_FAILED";

    private final VisionProperties properties;
    private final Map<String, VisionRecognitionProvider> providers;

    public ConfiguredVisionRecognitionService(VisionProperties properties, List<VisionRecognitionProvider> providers) {
        this.properties = properties;
        this.providers = providers.stream()
                .collect(Collectors.toMap(provider -> normalize(provider.providerName()), Function.identity()));
    }

    @Override
    public VisionRecognitionResult recognize(byte[] imageBytes, String mimeType) {
        return recognize(imageBytes, mimeType, VisionRecognitionOptions.of(properties.getProvider(), null));
    }

    @Override
    public VisionRecognitionResult recognize(byte[] imageBytes, String mimeType, VisionRecognitionOptions options) {
        String requestedProvider = options == null || !StringUtils.hasText(options.getProvider())
                ? properties.getProvider()
                : options.getProvider();
        String configuredProviderName = normalize(requestedProvider);
        if (!StringUtils.hasText(configuredProviderName)) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "Vision provider is not configured");
        }

        VisionRecognitionProvider configuredProvider = providers.get(configuredProviderName);
        if (configuredProvider == null) {
            throw new VisionRecognitionException(STATUS_MODEL_FAILED, "Unsupported vision provider: " + requestedProvider);
        }
        return configuredProvider.recognize(imageBytes, mimeType, options);
    }

    private String normalize(String providerName) {
        return providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
    }
}
