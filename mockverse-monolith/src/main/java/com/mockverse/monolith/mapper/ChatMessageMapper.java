package com.mockverse.monolith.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mockverse.monolith.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
