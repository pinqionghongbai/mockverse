package com.mockverse.interview.kafka;

/**
 * 异步判题请求（D20）。字段与 judge-service 侧的同名 record 一致，也与 MCP 工具
 * {@code judge_code} 的参数一致——三条路径喂给判题器的是同一组输入。
 *
 * @param submissionId   提交 ID（幂等键 + Kafka 消息 key）
 * @param code           候选人提交的完整 Java 源码
 * @param stdin          可选：运行时喂给程序的 stdin
 * @param expectedOutput 可选：期望 stdout
 * @param timeoutSeconds 可选：运行超时秒数
 */
public record JudgeRequestMessage(
        String submissionId,
        String code,
        String stdin,
        String expectedOutput,
        Integer timeoutSeconds) {
}
