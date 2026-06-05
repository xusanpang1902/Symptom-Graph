package com.symptomgraph.service.impl;

import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.ImageHashBloomFilterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImageHashBloomFilterWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageHashBloomFilterWarmup.class);

    private final ImageHashBloomFilterService bloomFilterService;
    private final CorpusRecordService corpusRecordService;

    public ImageHashBloomFilterWarmup(ImageHashBloomFilterService bloomFilterService,
                                      CorpusRecordService corpusRecordService) {
        this.bloomFilterService = bloomFilterService;
        this.corpusRecordService = corpusRecordService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!bloomFilterService.isEnabled()) {
            return;
        }

        List<String> imageHashes = corpusRecordService.listDistinctImageHashes();
        for (String imageHash : imageHashes) {
            bloomFilterService.add(imageHash);
        }
        log.info("Warmed image hash Bloom Filter with {} distinct hashes from MySQL", imageHashes.size());
    }
}
