package com.symptomgraph.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.symptomgraph.entity.CaptureRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaptureRecordMapper extends BaseMapper<CaptureRecord> {
}
