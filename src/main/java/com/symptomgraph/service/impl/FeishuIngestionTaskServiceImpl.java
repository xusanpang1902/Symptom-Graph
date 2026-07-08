package com.symptomgraph.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.symptomgraph.entity.FeishuIngestionTask;
import com.symptomgraph.mapper.FeishuIngestionTaskMapper;
import com.symptomgraph.service.FeishuIngestionTaskService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeishuIngestionTaskServiceImpl extends ServiceImpl<FeishuIngestionTaskMapper, FeishuIngestionTask>
        implements FeishuIngestionTaskService {

    @Override
    public FeishuIngestionTask getByEventId(String eventId) {
        return lambdaQuery()
                .eq(FeishuIngestionTask::getEventId, eventId)
                .one();
    }

    @Override
    public FeishuIngestionTask getByMessageImage(String messageId, String imageKey) {
        return lambdaQuery()
                .eq(FeishuIngestionTask::getMessageId, messageId)
                .eq(FeishuIngestionTask::getImageKey, imageKey)
                .one();
    }

    @Override
    public List<FeishuIngestionTask> listByCaptureRecordId(Long captureRecordId) {
        return lambdaQuery()
                .eq(FeishuIngestionTask::getCaptureRecordId, captureRecordId)
                .list();
    }
}
