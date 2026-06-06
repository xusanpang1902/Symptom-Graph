package com.symptomgraph.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.symptomgraph.entity.CaptureRecord;

import java.util.List;

public interface CaptureRecordService extends IService<CaptureRecord> {

    CaptureRecord getByCaptureId(String captureId);

    List<CaptureRecord> listByImageHash(String imageHash);
}
