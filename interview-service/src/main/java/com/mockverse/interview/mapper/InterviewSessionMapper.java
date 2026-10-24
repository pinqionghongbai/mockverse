package com.mockverse.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mockverse.interview.entity.InterviewSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {
}
