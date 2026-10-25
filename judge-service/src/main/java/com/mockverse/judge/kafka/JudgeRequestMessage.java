package com.mockverse.judge.kafka;

/**
 * 一次异步判题请求（D20）。
 *
 * <p>字段与 MCP 工具 {@code judge_code} 的参数一一对应——两条链路喂给 {@link
 * com.mockverse.judge.judge.JavaJudge} 的输入必须是同一组，否则"同步判"和"异步判"
 * 会得出不同结论，而这种差异极难在演示时被发现。
 *
 * @param submissionId   提交 ID。**幂等键**：同一条消息重复投递时靠它去重，
 *                       也是 Kafka 消息 key（同一提交永远落在同一分区，天然有序）
 * @param code           候选人提交的完整 Java 源码
 * @param stdin          可选：运行时喂给程序的 stdin
 * @param expectedOutput 可选：期望 stdout；给出时判题结果里会有 {@code outputMatched}
 * @param timeoutSeconds 可选：运行超时秒数，超过服务端上限会被夹到上限
 */
public record JudgeRequestMessage(
        String submissionId,
        String code,
        String stdin,
        String expectedOutput,
        Integer timeoutSeconds) {
}
