package com.symptomgraph.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.symptomgraph.entity.CorpusRecord;

import java.util.List;

public interface CorpusRecordService extends IService<CorpusRecord> {

    List<CorpusRecord> listByImageHash(String imageHash);

    boolean existsByImageHash(String imageHash);

    List<CorpusRecord> listByCaptureId(String captureId);

    boolean removeByImageHash(String imageHash);
}
