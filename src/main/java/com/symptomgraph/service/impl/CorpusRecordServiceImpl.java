package com.symptomgraph.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.symptomgraph.dto.CorpusQueryPage;
import com.symptomgraph.dto.CorpusQueryRequest;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mapper.CorpusRecordMapper;
import com.symptomgraph.service.CorpusRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CorpusRecordServiceImpl extends ServiceImpl<CorpusRecordMapper, CorpusRecord> implements CorpusRecordService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SUPPORTED_SEARCH_FIELDS = Set.of("rawContent", "contextTarget");
    private static final Set<String> SUPPORTED_PARSE_STATUSES = Set.of(
            "SUCCESS", "PROCESSING", "EMPTY_RESULT", "MODEL_FAILED", "PARSE_FAILED"
    );

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
    public boolean removeByImageHash(String imageHash) {
        return lambdaUpdate()
                .eq(CorpusRecord::getImageHash, imageHash)
                .remove();
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
