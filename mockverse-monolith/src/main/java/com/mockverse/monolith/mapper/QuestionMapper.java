package com.mockverse.monolith.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mockverse.monolith.entity.Question;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
