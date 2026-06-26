package com.symptomgraph.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.symptomgraph.dto.CorpusQueryPage;
import com.symptomgraph.dto.CorpusQueryRequest;
import com.symptomgraph.entity.CorpusRecord;

import java.util.List;

public interface CorpusRecordService extends IService<CorpusRecord> {

    List<CorpusRecord> listByImageHash(String imageHash);

    boolean existsByImageHash(String imageHash);

    List<String> listDistinctImageHashes();

    List<CorpusRecord> listByCaptureId(String captureId);

    CorpusQueryPage search(CorpusQueryRequest request);

    boolean removeByImageHash(String imageHash);
}
