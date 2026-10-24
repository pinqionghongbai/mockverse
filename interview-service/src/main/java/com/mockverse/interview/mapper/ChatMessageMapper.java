package com.mockverse.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mockverse.interview.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
