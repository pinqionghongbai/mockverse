package com.mockverse.judge.kafka;

import com.mockverse.judge.judge.JudgeResult;

/**
 * 判题结果消息（D20）。
 *
 * <p>{@link #result} 原样带上 {@link JudgeResult} 的全部客观事实。消费端
 * （interview-service）不需要理解每个字段的语义——它只负责把这段 JSON 推给客户端，
 * 由客户端/前端决定怎么展示。这样判题结果的结构演进不会逼着两个服务同时改。
 *
 * @param submissionId 对应 {@link JudgeRequestMessage#submissionId()}，也是消息 key
 * @param result       判题事实
 */
public record JudgeResultMessage(String submissionId, JudgeResult result) {
}
