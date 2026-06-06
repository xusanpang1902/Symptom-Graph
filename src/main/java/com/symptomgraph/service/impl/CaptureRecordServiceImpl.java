package com.symptomgraph.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.mapper.CaptureRecordMapper;
import com.symptomgraph.service.CaptureRecordService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CaptureRecordServiceImpl extends ServiceImpl<CaptureRecordMapper, CaptureRecord> implements CaptureRecordService {

    @Override
    public CaptureRecord getByCaptureId(String captureId) {
        return lambdaQuery()
                .eq(CaptureRecord::getCaptureId, captureId)
                .one();
    }

    @Override
    public List<CaptureRecord> listByImageHash(String imageHash) {
        return lambdaQuery()
                .eq(CaptureRecord::getImageHash, imageHash)
                .orderByDesc(CaptureRecord::getCreatedAt)
                .list();
    }
}
