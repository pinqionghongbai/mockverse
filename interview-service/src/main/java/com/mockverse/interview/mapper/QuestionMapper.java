package com.mockverse.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mockverse.interview.entity.Question;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
