package com.symptomgraph.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.symptomgraph.config.FeishuProperties;
import com.symptomgraph.integration.feishu.FeishuEventParser;
import com.symptomgraph.integration.feishu.FeishuImageIngestionService;
import com.symptomgraph.integration.feishu.FeishuImageMessageEvent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/feishu")
public class FeishuEventController {

    private final FeishuProperties properties;
    private final FeishuEventParser eventParser;
    private final FeishuImageIngestionService imageIngestionService;

    public FeishuEventController(FeishuProperties properties,
                                 FeishuEventParser eventParser,
                                 FeishuImageIngestionService imageIngestionService) {
        this.properties = properties;
        this.eventParser = eventParser;
        this.imageIngestionService = imageIngestionService;
    }

    @PostMapping("/events")
    public Map<String, Object> events(@RequestBody String rawBody) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Feishu integration is disabled");
        }
        try {
            JsonNode root = eventParser.parseBody(rawBody);
            if (eventParser.isUrlVerification(root)) {
                return Map.of("challenge", eventParser.challenge(root));
            }
            Optional<FeishuImageMessageEvent> imageMessageEvent = eventParser.parseImageMessageEvent(root);
            imageMessageEvent.ifPresent(imageIngestionService::ingest);
            return Map.of("code", 0);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
