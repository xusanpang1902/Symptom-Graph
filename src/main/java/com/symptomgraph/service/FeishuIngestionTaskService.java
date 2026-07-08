package com.symptomgraph.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.symptomgraph.entity.FeishuIngestionTask;

import java.util.List;

public interface FeishuIngestionTaskService extends IService<FeishuIngestionTask> {

    FeishuIngestionTask getByEventId(String eventId);

    FeishuIngestionTask getByMessageImage(String messageId, String imageKey);

    List<FeishuIngestionTask> listByCaptureRecordId(Long captureRecordId);
}
