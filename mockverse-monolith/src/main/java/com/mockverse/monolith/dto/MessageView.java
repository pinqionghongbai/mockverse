package com.mockverse.monolith.dto;

import java.time.LocalDateTime;

/** 历史消息条目。 */
public record MessageView(
        Integer seqNo,
        String role,
        String agentName,
        String content,
        LocalDateTime createdAt) {
}
