package com.mockverse.interview.dto;

/** 一轮问答的结果。 */
public record AnswerResponse(
        Long sessionId,
        int round,
        String agentMessage,
        boolean finished) {
}
