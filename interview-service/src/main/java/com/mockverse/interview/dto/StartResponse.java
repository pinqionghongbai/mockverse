package com.mockverse.interview.dto;

/** 开始面试的响应。 */
public record StartResponse(
        Long sessionId,
        String adkSessionId,
        Long questionId,
        String category,
        String difficulty,
        String question,
        String agentMessage) {
}
