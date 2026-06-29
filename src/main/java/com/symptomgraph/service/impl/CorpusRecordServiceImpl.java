package com.symptomgraph.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.symptomgraph.dto.CorpusAnalyticsResponse;
import com.symptomgraph.dto.CorpusQueryPage;
import com.symptomgraph.dto.CorpusQueryRequest;
import com.symptomgraph.dto.CorpusReviewRequest;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mapper.CorpusRecordMapper;
import com.symptomgraph.service.CorpusRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CorpusRecordServiceImpl extends ServiceImpl<CorpusRecordMapper, CorpusRecord> implements CorpusRecordService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SUPPORTED_SEARCH_FIELDS = Set.of("rawContent", "contextTarget");
    private static final Set<String> SUPPORTED_PARSE_STATUSES = Set.of(
            "SUCCESS", "PROCESSING", "EMPTY_RESULT", "MODEL_FAILED", "PARSE_FAILED"
    );
    private static final String REVIEW_STATUS_UNREVIEWED = "UNREVIEWED";
    private static final String REVIEW_STATUS_REVIEWED = "REVIEWED";
    private static final String REVIEW_STATUS_CORRECTED = "CORRECTED";
    private static final Set<String> SUPPORTED_REVIEW_STATUSES = Set.of(
            REVIEW_STATUS_UNREVIEWED, REVIEW_STATUS_REVIEWED, REVIEW_STATUS_CORRECTED
    );
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int TOP_DIMENSION_LIMIT = 20;

    private final ObjectMapper objectMapper;

    public CorpusRecordServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CorpusRecord> listByImageHash(String imageHash) {
        return lambdaQuery()
                .eq(CorpusRecord::getImageHash, imageHash)
                .orderByAsc(CorpusRecord::getCommentIndex)
                .list();
    }

    @Override
    public boolean existsByImageHash(String imageHash) {
        return lambdaQuery()
                .eq(CorpusRecord::getImageHash, imageHash)
                .exists();
    }

    @Override
    public List<String> listDistinctImageHashes() {
        return lambdaQuery()
                .select(CorpusRecord::getImageHash)
                .groupBy(CorpusRecord::getImageHash)
                .list()
                .stream()
                .map(CorpusRecord::getImageHash)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Override
    public List<CorpusRecord> listByCaptureId(String captureId) {
        return lambdaQuery()
                .eq(CorpusRecord::getCaptureId, captureId)
                .orderByAsc(CorpusRecord::getCommentIndex)
                .list();
    }

    @Override
    public CorpusQueryPage search(CorpusQueryRequest request) {
        SearchCriteria criteria = normalize(request);
        LambdaQueryWrapper<CorpusRecord> query = buildFilteredQuery(criteria);

        query.orderByDesc(CorpusRecord::getCollectedTime)
                .orderByDesc(CorpusRecord::getId);

        Page<CorpusRecord> result = page(new Page<>(criteria.page(), criteria.pageSize()), query);
        return new CorpusQueryPage(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                result.getRecords()
        );
    }

    @Override
    public CorpusAnalyticsResponse analytics(CorpusQueryRequest request) {
        SearchCriteria criteria = normalize(request);
        List<CorpusRecord> records = list(buildFilteredQuery(criteria));

        CorpusAnalyticsResponse response = new CorpusAnalyticsResponse();
        response.setTotalRecords(records.size());
        response.setDistinctCaptureCount(records.stream()
                .map(CorpusRecord::getCaptureId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet())
                .size());
        response.setParseStatusCounts(toCountItems(countByText(records, CorpusRecord::getParseStatus), TOP_DIMENSION_LIMIT));
        response.setReviewStatusCounts(toCountItems(countReviewStatuses(records), TOP_DIMENSION_LIMIT));
        response.setPlatformCounts(toCountItems(countByText(records, CorpusRecord::getPlatform), TOP_DIMENSION_LIMIT));
        response.setTagCounts(toCountItems(countTags(records), TOP_DIMENSION_LIMIT));
        response.setDailyCounts(toDailyCountItems(countDaily(records)));
        return response;
    }

    @Override
    public boolean removeByImageHash(String imageHash) {
        return lambdaUpdate()
                .eq(CorpusRecord::getImageHash, imageHash)
                .remove();
    }

    @Override
    public CorpusRecord review(Long id, CorpusReviewRequest request) {
        CorpusRecord record = getById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Corpus record not found");
        }
        if (request == null) {
            throw badRequest("Review request is required");
        }

        String reviewStatus = normalizeText(request.getReviewStatus());
        if (!StringUtils.hasText(reviewStatus)) {
            throw badRequest("reviewStatus is required");
        }
        if (!SUPPORTED_REVIEW_STATUSES.contains(reviewStatus)) {
            throw badRequest("Unsupported reviewStatus: " + reviewStatus);
        }

        applyReview(record, request, reviewStatus);
        persistReviewFields(record);
        return record;
    }

    private void persistReviewFields(CorpusRecord record) {
        lambdaUpdate()
                .eq(CorpusRecord::getId, record.getId())
                .set(CorpusRecord::getReviewStatus, record.getReviewStatus())
                .set(CorpusRecord::getReviewedRawContent, record.getReviewedRawContent())
                .set(CorpusRecord::getReviewedContextTarget, record.getReviewedContextTarget())
                .set(CorpusRecord::getReviewedTags, record.getReviewedTags())
                .set(CorpusRecord::getReviewedAt, record.getReviewedAt())
                .set(CorpusRecord::getReviewNote, record.getReviewNote())
                .update();
    }

    private void applyReview(CorpusRecord record, CorpusReviewRequest request, String reviewStatus) {
        record.setReviewStatus(reviewStatus);
        record.setReviewedAt(LocalDateTime.now());

        if (REVIEW_STATUS_UNREVIEWED.equals(reviewStatus)) {
            record.setReviewedRawContent(null);
            record.setReviewedContextTarget(null);
            record.setReviewedTags(null);
            record.setReviewNote(null);
            record.setReviewedAt(null);
            return;
        }

        if (REVIEW_STATUS_REVIEWED.equals(reviewStatus)) {
            record.setReviewedRawContent(null);
            record.setReviewedContextTarget(null);
            record.setReviewedTags(null);
            record.setReviewNote(normalizeText(request.getReviewNote()));
            return;
        }

        String reviewedRawContent = normalizeText(request.getReviewedRawContent());
        String reviewedContextTarget = normalizeText(request.getReviewedContextTarget());
        boolean tagsProvided = request.getReviewedTags() != null;
        if (!StringUtils.hasText(reviewedRawContent) && !StringUtils.hasText(reviewedContextTarget) && !tagsProvided) {
            throw badRequest("CORRECTED review requires at least one reviewed field");
        }

        record.setReviewedRawContent(reviewedRawContent);
        record.setReviewedContextTarget(reviewedContextTarget);
        record.setReviewedTags(tagsProvided ? toJson(sanitizeTags(request.getReviewedTags())) : null);
        record.setReviewNote(normalizeText(request.getReviewNote()));
    }

    private void applyKeywordCondition(LambdaQueryWrapper<CorpusRecord> query,
                                       String keyword,
                                       List<String> searchFields) {
        boolean searchRawContent = searchFields.contains("rawContent");
        boolean searchContextTarget = searchFields.contains("contextTarget");
        if (searchRawContent && searchContextTarget) {
            query.and(group -> group
                    .like(CorpusRecord::getRawContent, keyword)
                    .or()
                    .like(CorpusRecord::getContextTarget, keyword));
        } else if (searchRawContent) {
            query.like(CorpusRecord::getRawContent, keyword);
        } else {
            query.like(CorpusRecord::getContextTarget, keyword);
        }
    }

    private LambdaQueryWrapper<CorpusRecord> buildFilteredQuery(SearchCriteria criteria) {
        LambdaQueryWrapper<CorpusRecord> query = new LambdaQueryWrapper<>();
        query.eq(StringUtils.hasText(criteria.platform()), CorpusRecord::getPlatform, criteria.platform())
                .eq(StringUtils.hasText(criteria.parseStatus()), CorpusRecord::getParseStatus, criteria.parseStatus())
                .eq(StringUtils.hasText(criteria.captureId()), CorpusRecord::getCaptureId, criteria.captureId())
                .ge(criteria.collectedFrom() != null, CorpusRecord::getCollectedTime, criteria.collectedFrom())
                .lt(criteria.collectedTo() != null, CorpusRecord::getCollectedTime, criteria.collectedTo());

        if (StringUtils.hasText(criteria.tag())) {
            query.apply("JSON_CONTAINS(tags, JSON_QUOTE({0}))", criteria.tag());
        }
        if (StringUtils.hasText(criteria.keyword())) {
            applyKeywordCondition(query, criteria.keyword(), criteria.searchFields());
        }
        return query;
    }

    private Map<String, Long> countByText(List<CorpusRecord> records, java.util.function.Function<CorpusRecord, String> classifier) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CorpusRecord record : records) {
            increment(counts, displayName(classifier.apply(record)));
        }
        return counts;
    }

    private Map<String, Long> countReviewStatuses(List<CorpusRecord> records) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CorpusRecord record : records) {
            increment(counts, displayName(record.getReviewStatus() == null ? REVIEW_STATUS_UNREVIEWED : record.getReviewStatus()));
        }
        return counts;
    }

    private Map<String, Long> countTags(List<CorpusRecord> records) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CorpusRecord record : records) {
            for (String tag : parseTags(record.getTags())) {
                increment(counts, displayName(tag));
            }
        }
        return counts;
    }

    private Map<String, Long> countDaily(List<CorpusRecord> records) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CorpusRecord record : records) {
            String day = record.getCollectedTime() == null
                    ? "UNKNOWN"
                    : record.getCollectedTime().toLocalDate().format(DAY_FORMATTER);
            increment(counts, day);
        }
        return counts;
    }

    private List<CorpusAnalyticsResponse.CountItem> toCountItems(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> CorpusAnalyticsResponse.CountItem.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<CorpusAnalyticsResponse.CountItem> toDailyCountItems(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> CorpusAnalyticsResponse.CountItem.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void increment(Map<String, Long> counts, String key) {
        counts.merge(key, 1L, Long::sum);
    }

    private String displayName(String value) {
        return StringUtils.hasText(value) ? value.trim() : "UNKNOWN";
    }

    private List<String> parseTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private SearchCriteria normalize(CorpusQueryRequest request) {
        String platform = normalizeText(request.getPlatform());
        String parseStatus = normalizeText(request.getParseStatus());
        String tag = normalizeText(request.getTag());
        String captureId = normalizeText(request.getCaptureId());
        String keyword = normalizeText(request.getKeyword());
        LocalDateTime collectedFrom = request.getCollectedFrom();
        LocalDateTime collectedTo = request.getCollectedTo();

        if (StringUtils.hasText(parseStatus) && !SUPPORTED_PARSE_STATUSES.contains(parseStatus)) {
            throw badRequest("Unsupported parseStatus: " + parseStatus);
        }
        if (collectedFrom != null && collectedTo != null && !collectedFrom.isBefore(collectedTo)) {
            throw badRequest("collectedFrom must be earlier than collectedTo");
        }

        List<String> searchFields = normalizeSearchFields(keyword, request.getSearchFields());
        long page = request.getPage() == null ? DEFAULT_PAGE : Math.max(DEFAULT_PAGE, request.getPage());
        long pageSize = request.getPageSize() == null
                ? DEFAULT_PAGE_SIZE
                : Math.min(MAX_PAGE_SIZE, Math.max(1, request.getPageSize()));

        return new SearchCriteria(platform, parseStatus, tag, captureId, collectedFrom, collectedTo,
                keyword, searchFields, page, pageSize);
    }

    private List<String> normalizeSearchFields(String keyword, List<String> requestedFields) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        if (requestedFields == null || requestedFields.isEmpty()) {
            return List.of("rawContent", "contextTarget");
        }

        LinkedHashSet<String> normalizedFields = new LinkedHashSet<>();
        for (String requestedField : requestedFields) {
            String field = normalizeText(requestedField);
            if (!SUPPORTED_SEARCH_FIELDS.contains(field)) {
                throw badRequest("searchFields must contain only rawContent or contextTarget");
            }
            normalizedFields.add(field);
        }
        if (normalizedFields.isEmpty()) {
            throw badRequest("searchFields must contain only rawContent or contextTarget");
        }
        return List.copyOf(normalizedFields);
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> sanitizedTags = new LinkedHashSet<>();
        for (String tag : tags) {
            String sanitizedTag = normalizeText(tag);
            if (!StringUtils.hasText(sanitizedTag)) {
                continue;
            }
            while (sanitizedTag.startsWith("#") || sanitizedTag.startsWith("＃")) {
                sanitizedTag = sanitizedTag.substring(1).trim();
            }
            if (StringUtils.hasText(sanitizedTag)) {
                sanitizedTags.add(sanitizedTag);
            }
        }
        return List.copyOf(sanitizedTags);
    }

    private String toJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize reviewed tags", ex);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record SearchCriteria(String platform,
                                  String parseStatus,
                                  String tag,
                                  String captureId,
                                  LocalDateTime collectedFrom,
                                  LocalDateTime collectedTo,
                                  String keyword,
                                  List<String> searchFields,
                                  long page,
                                  long pageSize) {
    }
}
