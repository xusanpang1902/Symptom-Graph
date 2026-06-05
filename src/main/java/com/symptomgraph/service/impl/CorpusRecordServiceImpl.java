package com.symptomgraph.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.mapper.CorpusRecordMapper;
import com.symptomgraph.service.CorpusRecordService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class CorpusRecordServiceImpl extends ServiceImpl<CorpusRecordMapper, CorpusRecord> implements CorpusRecordService {

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
    public boolean removeByImageHash(String imageHash) {
        return lambdaUpdate()
                .eq(CorpusRecord::getImageHash, imageHash)
                .remove();
    }
}
