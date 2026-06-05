package com.symptomgraph.service.impl;

import com.symptomgraph.service.ImageHashBloomFilterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.bloom", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopImageHashBloomFilterServiceImpl implements ImageHashBloomFilterService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean mightContain(String imageHash) {
        return true;
    }

    @Override
    public void add(String imageHash) {
        // Bloom Filter is disabled; keep the original MySQL-based dedupe behavior.
    }
}
