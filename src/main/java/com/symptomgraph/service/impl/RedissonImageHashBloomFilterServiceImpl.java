package com.symptomgraph.service.impl;

import com.symptomgraph.config.BloomFilterProperties;
import com.symptomgraph.service.ImageHashBloomFilterService;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnBean(RedissonClient.class)
@ConditionalOnProperty(prefix = "app.bloom", name = "enabled", havingValue = "true")
public class RedissonImageHashBloomFilterServiceImpl implements ImageHashBloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(RedissonImageHashBloomFilterServiceImpl.class);

    private final RedissonClient redissonClient;
    private final BloomFilterProperties properties;
    private RBloomFilter<String> bloomFilter;

    public RedissonImageHashBloomFilterServiceImpl(RedissonClient redissonClient, BloomFilterProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        bloomFilter = redissonClient.getBloomFilter(properties.getName());
        bloomFilter.tryInit(properties.getExpectedInsertions(), properties.getFalseProbability());
        log.info("Initialized image hash Bloom Filter: name={}, expectedInsertions={}, falseProbability={}",
                properties.getName(), properties.getExpectedInsertions(), properties.getFalseProbability());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean mightContain(String imageHash) {
        if (!StringUtils.hasText(imageHash)) {
            return true;
        }
        try {
            return bloomFilter.contains(imageHash);
        } catch (RuntimeException ex) {
            log.warn("Bloom Filter lookup failed, falling back to MySQL dedupe: imageHash={}", imageHash, ex);
            return true;
        }
    }

    @Override
    public void add(String imageHash) {
        if (!StringUtils.hasText(imageHash)) {
            return;
        }
        try {
            bloomFilter.add(imageHash);
        } catch (RuntimeException ex) {
            log.warn("Bloom Filter add failed: imageHash={}", imageHash, ex);
        }
    }
}
