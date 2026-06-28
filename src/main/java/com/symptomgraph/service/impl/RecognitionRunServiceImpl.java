package com.symptomgraph.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.symptomgraph.entity.RecognitionRun;
import com.symptomgraph.mapper.RecognitionRunMapper;
import com.symptomgraph.service.RecognitionRunService;
import org.springframework.stereotype.Service;

@Service
public class RecognitionRunServiceImpl extends ServiceImpl<RecognitionRunMapper, RecognitionRun> implements RecognitionRunService {
}
